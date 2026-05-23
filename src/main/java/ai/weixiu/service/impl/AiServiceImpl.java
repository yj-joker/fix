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
import ai.weixiu.mq.MemoryMessageProducer;
import ai.weixiu.utils.BaseContext;
import ai.weixiu.utils.MultimodalEmbeddingUtils;
import ai.weixiu.utils.VoiceToTextUtils;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
@AllArgsConstructor
@Slf4j
public class AiServiceImpl implements AiService {
    private final AiSessionService aiSessionService;
    private final AiMessageService aiMessageService;
    private final WebClient webClient;
    private final MemoryMessageProducer memoryMessageProducer;
    private final RedisTemplate<String, Object> redisTemplate;
    private final MemoryPreferenceService memoryPreferenceService;
    private final MemoryUnresolvedService memoryUnresolvedService;
    private final MultimodalEmbeddingUtils multimodalEmbeddingUtils;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Integer maxMemory = 4;
    private final VoiceToTextUtils voiceToTextUtils ;


    @Override
    @Transactional
    public Flux<String> chat(AiChatRequest aiChatRequest) {
        String uri = "/ai/chat/stream";
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

        // ===== 图片URL转Base64（解决云端LLM无法访问本地MinIO的问题）=====
        if (aiChatRequest.getImages() != null && !aiChatRequest.getImages().isEmpty()) {
            List<String> base64Images = multimodalEmbeddingUtils.downloadImagesToBase64(aiChatRequest.getImages());
            aiChatRequest.setImages(base64Images);
            log.info("已将{}张MinIO图片转为Base64", base64Images.size());
        }

        log.info("最终消息: {}", aiChatRequest.getUserMessage());
        Flux<String> flux = getStringFlux(aiChatRequest, uri);
        StringBuilder fullResponse = new StringBuilder();
        AiSession finalAiSession = aiSession;
        return flux
                .doOnNext(fullResponse::append)  // 收集每个token
                .doOnComplete(() -> {
                    saveAiReply(finalAiSession, userId, fullResponse);

                    // ===== 实时记忆更新：发MQ消息，Python异步消费 =====
                    memoryMessageProducer.sendRealtimeUpdate(
                            finalAiSession.getId(),
                            userId,
                            aiChatRequest.getUserMessage(),
                            fullResponse.toString()
                    );

                    // ===== 定时整合：每maxMemory轮发MQ消息 =====
                    if (finalAiSession.getRoundCount() % maxMemory == 0) {
                        memoryMessageProducer.sendConsolidate(
                                finalAiSession.getId(), userId,
                                finalAiSession.getRoundCount(), maxMemory
                        );
                    }
                });
    }

    /*
     * 声音->文本(本地部署语音识别大模型)
     * */

    @Override
    public String getStringByVoiceViaLLM(MultipartFile file) {
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

    /*
     * 声音->文本(调用百度语音识别大模型)
     * */
    @Override
    public String getStringByVoiceViaBaidu(MultipartFile file) {
        return voiceToTextUtils.transcribe(file);
    }

    private boolean isValid(MultipartFile file) {
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

    /**
     * 组装最终发送给AI的完整上下文
     *
     * 上下文包含6部分（按优先级排列）：
     * 1. previousContext  - 上一轮整合的渐进式摘要（让AI知道"之前聊了什么"）
     * 2. relevantFacts    - 从向量库检索的相关事实（让AI"记住"历史事实，防止幻觉）
     * 3. userPreferences  - 用户级偏好（跨会话通用，如"回复用中文"）
     * 4. sessionPreferences - 会话级偏好（仅本次会话，如"这次用表格展示"）
     * 5. messages         - 当前滑动窗口内的原始对话消息
     * 6. unresolvedItems  - 还没解决的待办事项
     *
     * 【为什么要检索事实？】
     * 之前事实提取了但从不使用，等于白做。现在通过向量检索把相关事实注入上下文，
     * AI回答时能看到之前的事实记录，大大减少幻觉（编造不存在的信息）。
     */
    private void finalAiContext(AiChatRequest aiChatRequest, Long sessionId, Long userId, List<MemoryMessage> memoryMessages) {
        // ========== 保留原始用户消息（后续realtime更新需要用到） ==========
        String originalUserMessage = aiChatRequest.getUserMessage();

        // ========== 1. 获取上一轮的摘要（作为对话背景） ==========
        AiSession currentSession = aiSessionService.getById(sessionId);
        String previousSummary = (currentSession != null) ? currentSession.getSummary() : null;

        // ========== 2/3/4. 三个独立查询并行执行（节省 ~100-150ms） ==========
        // 事实检索（调用Python向量库，最慢 ~200ms）
        CompletableFuture<List<JSONObject>> factsFuture =
                CompletableFuture.supplyAsync(() -> searchRelevantFacts(originalUserMessage, userId));

        // 偏好查询（Redis缓存或MySQL，~50ms）
        CompletableFuture<List<MemoryPreference>> preferencesFuture =
                CompletableFuture.supplyAsync(() -> loadPreferences(sessionId, userId));

        // 待办查询（MySQL，~50ms）
        CompletableFuture<List<MemoryUnresolved>> unresolvedFuture =
                CompletableFuture.supplyAsync(() -> memoryUnresolvedService.getUnresolved(sessionId));

        // 等待三个任务全部完成
        CompletableFuture.allOf(factsFuture, preferencesFuture, unresolvedFuture).join();

        List<JSONObject> relevantFacts = factsFuture.join();
        List<MemoryPreference> allPreferences = preferencesFuture.join();
        List<MemoryUnresolved> unresolved = unresolvedFuture.join();

        // ========== 5. 转换为VO对象 ==========
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

        // ========== 6. 构建多轮对话历史（OpenAI格式） ==========
        // 注意：memoryMessages最后一条是刚保存的当前轮user消息，需要排除
        // 因为当前轮消息会作为独立的message字段发送，避免重复
        List<Map<String, String>> conversationHistory = new ArrayList<>();
        for (int i = 0; i < memoryMessages.size(); i++) {
            MemoryMessage msg = memoryMessages.get(i);
            // 排除最后一条（当前轮user消息），它已经在message字段中
            if (i == memoryMessages.size() - 1 && "user".equals(msg.getRole())) {
                break;
            }
            Map<String, String> turn = new HashMap<>();
            turn.put("role", msg.getRole());
            turn.put("content", msg.getContent());
            conversationHistory.add(turn);
        }
        aiChatRequest.setConversationHistory(conversationHistory);

        // ========== 7. 构建结构化上下文（注入system prompt） ==========
        Map<String, Object> contextMap = new HashMap<>();
        if (previousSummary != null && !previousSummary.isEmpty()) {
            contextMap.put("previous_summary", previousSummary);
        }
        if (!relevantFacts.isEmpty()) {
            contextMap.put("relevant_facts", relevantFacts);
        }
        if (!userPrefVOs.isEmpty()) {
            contextMap.put("user_preferences", userPrefVOs);
        }
        if (!sessionPrefVOs.isEmpty()) {
            contextMap.put("session_preferences", sessionPrefVOs);
        }
        if (!unresolvedVOs.isEmpty()) {
            contextMap.put("unresolved_items", unresolvedVOs);
        }
        aiChatRequest.setContext(contextMap);

        // ========== 8. userMessage保持为当前用户的原始消息 ==========
        // 不再覆盖为JSON大块，Python端能正确识别当前轮用户说了什么
        aiChatRequest.setUserMessage(originalUserMessage);
    }

    /**
     * 加载用户偏好（优先Redis缓存，未命中则查MySQL并回写缓存）
     */
    private List<MemoryPreference> loadPreferences(Long sessionId, Long userId) {
        String cacheKey = RedisKey.PREFERENCE_CACHE + userId + ":" + sessionId;
        CachedPreferences cached = (CachedPreferences) redisTemplate.opsForValue().get(cacheKey);

        if (cached != null) {
            List<MemoryPreference> result = new ArrayList<>();
            if (cached.getUserPreferences() != null) {
                result.addAll(cached.getUserPreferences());
            }
            if (cached.getSessionPreferences() != null) {
                result.addAll(cached.getSessionPreferences());
            }
            return result;
        }

        List<MemoryPreference> allPreferences = memoryPreferenceService.getPreference(sessionId, userId);
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
        return allPreferences;
    }

    /**
     * 调用Python端向量检索接口，查找与用户消息语义最相关的历史事实
     *
     * 【工作原理】
     * 1. 把用户当前发送的消息作为查询文本发给Python端
     * 2. Python端用text-embedding模型把查询文本转为向量
     * 3. 在Redis向量库中做KNN近邻搜索，找到最相似的事实记录
     * 4. 返回top 5条最相关的事实
     *
     * 这些事实会被注入到AI对话上下文中，让AI能"记住"之前提取的事实。
     *
     * @param userMessage 用户当前发送的消息
     * @return 相关事实列表，每条包含 content/score/keywords 等字段
     */
    private List<JSONObject> searchRelevantFacts(String userMessage, Long userId) {
        try {
            // 查出当前用户的所有会话ID，用于过滤事实（防止检索到其他用户的事实）
            LambdaQueryWrapper<AiSession> sessionQuery = new LambdaQueryWrapper<>();
            sessionQuery.eq(AiSession::getUserId, userId).select(AiSession::getId);
            List<String> userSessionIds = aiSessionService.list(sessionQuery)
                    .stream().map(s -> s.getId().toString()).toList();

            // 调用Python端的事实检索接口
            String response = webClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/ai/memory/search_facts")
                            .queryParam("query", userMessage)
                            .queryParam("top_k", 5)
                            .queryParam("session_ids", String.join(",", userSessionIds))
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            if (response != null) {
                JSONObject result = JSONUtil.parseObj(response);
                cn.hutool.json.JSONArray facts = result.getJSONArray("facts");
                if (facts != null && !facts.isEmpty()) {
                    List<JSONObject> factList = new ArrayList<>();
                    for (int i = 0; i < facts.size(); i++) {
                        factList.add(facts.getJSONObject(i));
                    }
                    log.info("向量检索到{}条相关事实", factList.size());
                    return factList;
                }
            }
        } catch (Exception e) {
            // 事实检索失败不影响主流程，降级为没有事实记忆
            log.warn("事实记忆向量检索失败，降级运行: {}", e.getMessage());
        }
        return new ArrayList<>();
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

}