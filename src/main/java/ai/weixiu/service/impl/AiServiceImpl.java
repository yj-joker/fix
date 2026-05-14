package ai.weixiu.service.impl;

import ai.weixiu.common.RedisKey;
import ai.weixiu.entity.AiChatRequest;
import ai.weixiu.entity.AiMessage;
import ai.weixiu.entity.AiSession;
import ai.weixiu.entity.CachedPreferences;
import ai.weixiu.entity.MemoryMessage;
import ai.weixiu.entity.MemoryPreference;
import ai.weixiu.entity.MemoryUnresolved;
import ai.weixiu.enumerate.MemoryStatusEnum;
import ai.weixiu.enumerate.PreferenceCategoryEnum;
import ai.weixiu.exceprion.AiMemoryException;
import ai.weixiu.exceprion.FormatErrorException;
import ai.weixiu.pojo.vo.MemoryPreferenceVO;
import ai.weixiu.pojo.vo.MemoryUnresolvedVO;
import ai.weixiu.service.AiMessageService;
import ai.weixiu.service.AiService;
import ai.weixiu.service.AiSessionService;
import ai.weixiu.service.MemoryPreferenceService;
import ai.weixiu.service.MemoryUnresolvedService;
import ai.weixiu.utils.AsyncUtils;
import ai.weixiu.utils.BaseContext;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@AllArgsConstructor
@Slf4j
public class AiServiceImpl implements AiService {
    private final AiSessionService aiSessionService;
    private final AiMessageService aiMessageService;
    private final WebClient webClient;
    private final AsyncUtils asyncUtils;
    private final RedisTemplate<String, Object> redisTemplate;
    private final MemoryPreferenceService memoryPreferenceService;
    private final MemoryUnresolvedService memoryUnresolvedService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Integer maxMemory = 4;


    @Override
    @Transactional
    public Flux<String> chat(AiChatRequest aiChatRequest) {
        String uri = "/ai/chat/stream";
        if (aiChatRequest.getUrl() != null) {
            //含有图片
        }
        Long userId = BaseContext.getCurrentId();
        LocalDateTime now = LocalDateTime.now();
        //查找当前会话记忆
        AiSession aiSession = aiSessionService.findMemory(aiChatRequest.getSessionId(), userId);
        List<MemoryMessage> memoryMessages = new ArrayList<>();
        if (aiSession == null) {
            //新会话,封装会话并保存
            aiSession = saveAiSession(aiChatRequest, now, userId);
        } else {
            ifOldMemory(aiChatRequest, aiSession, now, userId, memoryMessages);
        }
        // 将历史信息、偏好和待办事项拼接并设置到aiChatRequest
        finalAiContext(aiChatRequest, aiSession.getId(), userId, memoryMessages);
        log.info("最终消息: {}", aiChatRequest.getUserMessage());
        log.info("最终对象的JSON格式: {}", JSONUtil.toJsonStr(aiChatRequest));
        Flux<String> flux = getStringFlux(aiChatRequest, uri);
        StringBuilder fullResponse = new StringBuilder();
        AiSession finalAiSession = aiSession;
        return flux
                .doOnNext(fullResponse::append)  // 收集每个token
                .doOnComplete(() -> {
                    saveAiReply(finalAiSession, userId, fullResponse);
                    //判断此次回复完毕之后是否需要保存记忆
                    //当前对话超过最大记忆数,并且不是第一次超过,那么需要整合记忆
                    if (finalAiSession.getRoundCount() % maxMemory == 0) {
                        //异步整合记忆
                        asyncUtils.integrationMemory(finalAiSession.getRoundCount(), finalAiSession.getId(), userId, maxMemory);
                    }
                });
    }

    /*
     * 声音->文本
     * */

    @Override
    public String getStringByVoice(MultipartFile file) {
        boolean valid = isValid(file);
        if (!valid) {
            throw new FormatErrorException("不支持的语音文件格式");
        }
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", file.getResource());
        String response = webClient.post()
                .uri("/api/asr/transcribe")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(String.class)
                .block();
        JSONObject json = JSONUtil.parseObj(response);
        if (!json.getBool("success", false)) {
            throw new FormatErrorException("语音识别失败");
        }
        String text = json.getStr("text", "");
        log.info("语音识别结果: {}", text);
        return text;

    }

    private  boolean isValid(MultipartFile file) {
        String contentType = file.getContentType();
        String filename = file.getOriginalFilename();
        boolean valid = false;
        if (contentType != null && (contentType.startsWith("audio/") || contentType.equals("video/webm"))) {
            valid = true;
        }
        if (!valid && filename != null) {
            String lower = filename.toLowerCase();
            valid = lower.endsWith(".wav") || lower.endsWith(".mp3") || lower.endsWith(".flac")
                    || lower.endsWith(".aac") || lower.endsWith(".ogg") || lower.endsWith(".webm")
                    || lower.endsWith(".m4a") || lower.endsWith(".wma");
        }
        return valid;
    }

    private void finalAiContext(AiChatRequest aiChatRequest, Long sessionId, Long userId, List<MemoryMessage> memoryMessages) {
        String cacheKey = RedisKey.PREFERENCE_CACHE + userId + ":" + sessionId;
        CachedPreferences cached = (CachedPreferences) redisTemplate.opsForValue().get(cacheKey);

        List<MemoryPreference> allPreferences;
        if (cached != null) {
            allPreferences = new ArrayList<>();
            if (cached.getUserPreferences() != null) {
                allPreferences.addAll(cached.getUserPreferences());
            }
            if (cached.getSessionPreferences() != null) {
                allPreferences.addAll(cached.getSessionPreferences());
            }
        } else {
            allPreferences = memoryPreferenceService.getPreference(sessionId, userId);
            if (!allPreferences.isEmpty()) {
                List<MemoryPreference> userPrefs = new ArrayList<>();
                List<MemoryPreference> sessionPrefs = new ArrayList<>();
                for (MemoryPreference pref : allPreferences) {
                    if (pref.getPreferenceCategory() != null
                            && pref.getPreferenceCategory() == PreferenceCategoryEnum.USER_PREFERENCE.getCategory()) {
                        userPrefs.add(pref);
                    } else {
                        sessionPrefs.add(pref);
                    }
                }
                CachedPreferences toCache = new CachedPreferences(userPrefs, sessionPrefs);
                redisTemplate.opsForValue().set(cacheKey, toCache, 5, TimeUnit.HOURS);
            }
        }

        List<MemoryUnresolved> unresolved = memoryUnresolvedService.getUnresolved(sessionId);

        List<MemoryPreferenceVO> userPrefVOs = new ArrayList<>();
        List<MemoryPreferenceVO> sessionPrefVOs = new ArrayList<>();
        for (MemoryPreference pref : allPreferences) {
            MemoryPreferenceVO vo = new MemoryPreferenceVO();
            vo.setContent(pref.getContent());
            vo.setCategory(pref.getCategory());
            vo.setPreferenceCategory(pref.getPreferenceCategory());
            if (pref.getPreferenceCategory() != null
                    && pref.getPreferenceCategory() == PreferenceCategoryEnum.USER_PREFERENCE.getCategory()) {
                userPrefVOs.add(vo);
            } else {
                sessionPrefVOs.add(vo);
            }
        }

        List<MemoryUnresolvedVO> unresolvedVOs = new ArrayList<>();
        for (MemoryUnresolved item : unresolved) {
            MemoryUnresolvedVO vo = new MemoryUnresolvedVO();
            vo.setContent(item.getContent());
            vo.setType(item.getType());
            vo.setStatus(item.getStatus());
            unresolvedVOs.add(vo);
        }

        JSONObject finalContext = new JSONObject();
        finalContext.set("messages", memoryMessages);
        finalContext.set("userPreferences", userPrefVOs);
        finalContext.set("sessionPreferences", sessionPrefVOs);
        finalContext.set("unresolvedItems", unresolvedVOs);

        aiChatRequest.setUserMessage(JSONUtil.toJsonStr(finalContext));
    }

    private @NonNull Flux<String> getStringFlux(AiChatRequest aiChatRequest, String uri) {
        return webClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(aiChatRequest)
                .retrieve()
                .bodyToFlux(String.class)
                .map(line -> line.startsWith("data:") ? line.substring(5).trim() : line.trim())
                .filter(line -> !line.isEmpty())
                .flatMap(json -> {
                    try {
                        JsonNode root = objectMapper.readTree(json);
                        if (!"token".equals(root.path("event").asText())) {
                            return Flux.empty();
                        }
                        String content = root.path("data").path("content").asText("");
                        if (content.isEmpty()) {
                            return Flux.empty();
                        }
                        return Flux.just(content);
                    } catch (Exception e) {
                        return Flux.empty();
                    }
                });
    }

    private void saveAiReply(AiSession finalAiSession, Long userId, StringBuilder fullResponse) {
        // 流结束后保存AI回复
        AiMessage assistantMessage = new AiMessage();
        assistantMessage.setAiSessionId(finalAiSession.getId());
        assistantMessage.setUserId(userId);
        assistantMessage.setRoundNo(finalAiSession.getRoundCount());
        assistantMessage.setRole("assistant");
        assistantMessage.setContent(fullResponse.toString());
        assistantMessage.setCreatedAt(LocalDateTime.now());
        aiMessageService.save(assistantMessage);
    }

    private void ifOldMemory(AiChatRequest aiChatRequest, AiSession aiSession, LocalDateTime now, Long userId, List<MemoryMessage> memoryMessages) {
        List<AiMessage> aiMessage;
        if (aiSession.getId() == null) {
            throw new AiMemoryException("会话不存在");
        }
        aiSession.setUpdatedAt(now);
        aiSession.setRoundCount(aiSession.getRoundCount() + 1);
        //保存本轮userMessage
        AiMessage userMessage = new AiMessage();
        userMessage.setAiSessionId(aiSession.getId());
        userMessage.setUserId(userId);
        userMessage.setRoundNo(aiSession.getRoundCount());
        userMessage.setRole("user");
        userMessage.setContent(aiChatRequest.getUserMessage());
        userMessage.setCreatedAt(now);
        aiMessageService.save(userMessage);

        aiMessage = aiMessageService.findMemory(aiSession.getId(), userId, maxMemory, aiSession.getRoundCount());
        log.info("历史消息: {}", JSONUtil.toJsonStr(aiMessage));
        for (AiMessage msg : aiMessage) {
            MemoryMessage memoryMessage = new MemoryMessage();
            memoryMessage.setRole(msg.getRole());
            memoryMessage.setContent(msg.getContent());
            memoryMessages.add(memoryMessage);
        }
        aiSessionService.updateById(aiSession);
    }

    private AiSession saveAiSession(AiChatRequest aiChatRequest, LocalDateTime now, Long userId) {
        //保存会话
        AiSession aiSession;
        aiSession = new AiSession();
        aiSession.setId(Long.valueOf(aiChatRequest.getSessionId()));

        aiSession.setUserId(userId);
        //取前10字作为标题
        String title = aiChatRequest.getUserMessage();
        if (title != null && title.length() > 10) {
            title = title.substring(0, 10) + "...";  // 前10字 + "..."
        } else if (title == null || title.isBlank()) {
            title = "新对话";
        }
        aiSession.setTitle(title);
        aiSession.setStatus(MemoryStatusEnum.ACTIVE.getValue());
        aiSession.setRoundCount(1);
        aiSession.setUpdatedAt(now);
        aiSession.setCreatedAt(now);
        aiSessionService.save(aiSession);
        //保存AiMessage
        AiMessage aiMessage = new AiMessage();
        aiMessage.setAiSessionId(aiSession.getId());
        aiMessage.setUserId(userId);
        aiMessage.setRoundNo(1);
        aiMessage.setRole("user");
        aiMessage.setContent(aiChatRequest.getUserMessage());
        aiMessage.setCreatedAt(now);
        aiMessageService.save(aiMessage);
        return aiSession;
    }

    private boolean havePicture(File file) {
        return false;
    }
}