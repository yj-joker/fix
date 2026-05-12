package ai.weixiu.utils;

import ai.weixiu.entity.AiMessage;
import ai.weixiu.entity.MemoryMessage;
import ai.weixiu.entity.MemoryPreference;
import ai.weixiu.entity.MemoryUnresolved;
import ai.weixiu.pojo.vo.MemoryIntegrationParametersVO;
import ai.weixiu.pojo.vo.MemoryPreferenceVO;
import ai.weixiu.pojo.vo.MemoryUnresolvedVO;
import ai.weixiu.service.AiMessageService;
import ai.weixiu.service.MemoryFactService;
import ai.weixiu.service.MemoryPreferenceService;
import ai.weixiu.service.MemoryUnresolvedService;
import cn.hutool.json.JSONUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;

@Component
@AllArgsConstructor
@Slf4j
public class AsyncUtils {
    private AiMessageService aiMessageService;
    private MemoryFactService memoryFactService;
    private MemoryPreferenceService memoryPreferenceService;
    private MemoryUnresolvedService memoryUnresolvedService;


    //异步整合记忆
    @Async
    public void integrationMemory(Integer roundCount, Long sessionId,Long userId,Integer maxMemory){
        MemoryIntegrationParametersVO memoryIntegrationParameters = getMemoryIntegrationParametersVO(roundCount, sessionId, userId, maxMemory);
        String memoryIntegrationParametersToString = JSONUtil.toJsonStr(memoryIntegrationParameters);
        log.info("此次用户整合记忆发送:{}",memoryIntegrationParametersToString);
        WebClient webClient = WebClient.builder()
                .baseUrl("http://127.0.0.1:8000")
                .defaultHeader("Content-Type", "application/json")
                .build();
        //调用模型,获取模型生成的摘要
        String summary = webClient.post()
                .uri("/ai/memory/consolidate")
                .bodyValue(memoryIntegrationParametersToString)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    private @NonNull MemoryIntegrationParametersVO  getMemoryIntegrationParametersVO(Integer roundCount, Long sessionId, Long userId, Integer maxMemory) {
        //获取进30条记忆
        List<AiMessage> needIntegrationMemory = aiMessageService.getNeedIntegrationMemory(roundCount, sessionId, userId, maxMemory);
        List<MemoryMessage> memoryMessages=new ArrayList<>();
        for (AiMessage msg : needIntegrationMemory) {
            MemoryMessage memoryMessage = new MemoryMessage();
            memoryMessage.setRole(msg.getRole());
            memoryMessage.setContent(msg.getContent());
            memoryMessages.add(memoryMessage);
        }
        //获取长期偏好记忆 用户级加当前会话级
        List<MemoryPreference> preference = memoryPreferenceService.getPreference(sessionId, userId);
        List<MemoryPreferenceVO> memoryPreferenceVO = getMemoryPreferenceVO(preference);
        //获取未完成摘要
        List<MemoryUnresolved> memoryUnresolved = memoryUnresolvedService.getUnresolved(sessionId);
        List<MemoryUnresolvedVO> memoryUnresolvedVO = getMemoryUnresolvedVO(memoryUnresolved);
        MemoryIntegrationParametersVO memoryIntegrationParameters = new MemoryIntegrationParametersVO();
        memoryIntegrationParameters.setSessionId(sessionId.toString());
        memoryIntegrationParameters.setMemoryMessages(memoryMessages);
        memoryIntegrationParameters.setMemoryPreferenceVOList(memoryPreferenceVO);
        memoryIntegrationParameters.setMemoryUnresolvedVOList(memoryUnresolvedVO);
        return memoryIntegrationParameters;
    }

    private List<MemoryUnresolvedVO> getMemoryUnresolvedVO(List<MemoryUnresolved> memoryUnresolved) {
        List<MemoryUnresolvedVO> memoryUnresolvedVOList = new ArrayList<>();
        for (MemoryUnresolved memoryUnresolved1 : memoryUnresolved) {
            MemoryUnresolvedVO memoryUnresolvedVO = new MemoryUnresolvedVO();
            memoryUnresolvedVO.setContent(memoryUnresolved1.getContent());
            memoryUnresolvedVO.setType(memoryUnresolved1.getType());
            memoryUnresolvedVO.setStatus(memoryUnresolved1.getStatus());
            memoryUnresolvedVOList.add(memoryUnresolvedVO);
        }
        return memoryUnresolvedVOList;
    }

    private List<MemoryPreferenceVO> getMemoryPreferenceVO(List<MemoryPreference> preference) {
        List<MemoryPreferenceVO> memoryPreferenceVOList = new ArrayList<>();
        for (MemoryPreference memoryPreference : preference) {
            MemoryPreferenceVO memoryPreferenceVO = new MemoryPreferenceVO();
            memoryPreferenceVO.setContent(memoryPreference.getContent());
            memoryPreferenceVO.setCategory(memoryPreference.getCategory());
            memoryPreferenceVO.setPreferenceCategory(memoryPreference.getPreferenceCategory());
            memoryPreferenceVOList.add(memoryPreferenceVO);
        }
        return memoryPreferenceVOList;
    }

}
