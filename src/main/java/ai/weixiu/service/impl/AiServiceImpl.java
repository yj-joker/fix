package ai.weixiu.service.impl;

import ai.weixiu.entity.AiChatRequest;
import ai.weixiu.entity.AiMessage;
import ai.weixiu.entity.AiSession;
import ai.weixiu.entity.MemoryMessage;
import ai.weixiu.enumerate.MemoryStatusEnum;
import ai.weixiu.exceprion.AiMemoryException;
import ai.weixiu.service.AiMessageService;
import ai.weixiu.service.AiService;
import ai.weixiu.service.AiSessionService;
import ai.weixiu.utils.AsyncUtils;
import ai.weixiu.utils.BaseContext;
import cn.hutool.json.JSONUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@AllArgsConstructor
@Slf4j
public class AiServiceImpl implements AiService {
    private final AiSessionService aiSessionService;
    private final AiMessageService aiMessageService;
    private final WebClient webClient;
    private final AsyncUtils asyncUtils;
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
        // 组合历史消息
        memoryMessages.add(new MemoryMessage("user", aiChatRequest.getUserMessage()));
        String finalUserMessage = JSONUtil.toJsonStr(memoryMessages);
        aiChatRequest.setUserMessage(finalUserMessage);


        log.info("最终消息: {}", aiChatRequest.getUserMessage());
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