package ai.weixiu.utils;

import ai.weixiu.entity.AiMessage;
import ai.weixiu.entity.AiSession;
import ai.weixiu.entity.MemoryFact;
import ai.weixiu.entity.MemoryMessage;
import ai.weixiu.entity.MemoryPreference;
import ai.weixiu.entity.MemoryUnresolved;
import ai.weixiu.pojo.vo.MemoryIntegrationParametersVO;
import ai.weixiu.pojo.vo.MemoryPreferenceVO;
import ai.weixiu.pojo.vo.MemoryUnresolvedVO;
import ai.weixiu.service.AiMessageService;
import ai.weixiu.service.AiSessionService;
import ai.weixiu.service.MemoryFactService;
import ai.weixiu.service.MemoryPreferenceService;
import ai.weixiu.service.MemoryUnresolvedService;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@AllArgsConstructor
@Slf4j
public class AsyncUtils {
    private AiMessageService aiMessageService;
    private AiSessionService aiSessionService;
    private MemoryFactService memoryFactService;
    private MemoryPreferenceService memoryPreferenceService;
    private MemoryUnresolvedService memoryUnresolvedService;


    //异步整合记忆
    @Async
    public void integrationMemory(Integer roundCount, Long sessionId,Long userId,Integer maxMemory){
        List<AiMessage> needIntegrationMemory = aiMessageService.getNeedIntegrationMemory(roundCount, sessionId, userId, maxMemory);
        MemoryIntegrationParametersVO memoryIntegrationParameters = getMemoryIntegrationParametersVO(sessionId, userId, needIntegrationMemory);
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
        //保存模型生成的摘要
        saveSummary(summary, sessionId, userId, needIntegrationMemory);
    }

    private void saveSummary(String summary, Long sessionId, Long userId, List<AiMessage> needIntegrationMemory) {
        String sessionIdStr = sessionId.toString();
        JSONObject summaryJson = JSONUtil.parseObj(summary);
        JSONObject summaryData = summaryJson.getJSONObject("summary");
        if (summaryData == null) {
            log.warn("摘要数据为空, sessionId:{}", sessionIdStr);
            return;
        }

        // 计算下一次压缩序号
        int consolidationSeq = getNextConsolidationSeq(sessionIdStr);

        // 1. 保存newFacts到memory_fact
        JSONArray newFacts = summaryData.getJSONArray("newFacts");
        if (newFacts != null && !newFacts.isEmpty()) {
            List<MemoryFact> facts = new ArrayList<>();
            for (int i = 0; i < newFacts.size(); i++) {
                JSONObject fact = newFacts.getJSONObject(i);
                MemoryFact memoryFact = new MemoryFact();
                memoryFact.setSessionId(sessionIdStr);
                memoryFact.setFactId(UUID.randomUUID().toString());
                memoryFact.setContent(fact.getStr("content"));
                memoryFact.setKeywords(fact.getStr("keywords"));
                memoryFact.setSourceSeqRange(fact.getStr("sourceSeqRange"));
                memoryFact.setStatus("active");
                facts.add(memoryFact);
            }
            memoryFactService.saveBatch(facts);
            log.info("保存newFacts成功, 数量:{}", facts.size());
        }

        // 2. 更新supersededIds对应的memory_fact状态
        JSONArray supersededIds = summaryData.getJSONArray("supersededIds");
        if (supersededIds != null && !supersededIds.isEmpty()) {
            List<String> factIds = new ArrayList<>();
            for (int i = 0; i < supersededIds.size(); i++) {
                factIds.add(supersededIds.getStr(i));
            }
            LambdaUpdateWrapper<MemoryFact> factWrapper = new LambdaUpdateWrapper<>();
            factWrapper.in(MemoryFact::getFactId, factIds)
                    .set(MemoryFact::getStatus, "superseded")
                    .set(MemoryFact::getSupersededAt, LocalDateTime.now());
            memoryFactService.update(factWrapper);
            log.info("更新supersededIds成功, 数量:{}", factIds.size());
        }

        // 3. 保存updatedPreferences到memory_preference
        JSONArray updatedPreferences = summaryData.getJSONArray("updatedPreferences");
        if (updatedPreferences != null && !updatedPreferences.isEmpty()) {
            List<MemoryPreference> preferences = new ArrayList<>();
            for (int i = 0; i < updatedPreferences.size(); i++) {
                JSONObject pref = updatedPreferences.getJSONObject(i);
                MemoryPreference memoryPreference = new MemoryPreference();
                memoryPreference.setSessionId(sessionIdStr);
                memoryPreference.setUserId(userId);
                memoryPreference.setContent(pref.getStr("content"));
                memoryPreference.setCategory(pref.getStr("category"));
                memoryPreference.setPreferenceCategory(pref.getInt("preferenceCategory"));
                memoryPreference.setConsolidationSeq(consolidationSeq);
                preferences.add(memoryPreference);
            }
            memoryPreferenceService.saveBatch(preferences);
            log.info("保存updatedPreferences成功, 数量:{}", preferences.size());
        }

        // 4. 保存updatedUnresolved到memory_unresolved
        JSONArray updatedUnresolved = summaryData.getJSONArray("updatedUnresolved");
        if (updatedUnresolved != null && !updatedUnresolved.isEmpty()) {
            List<MemoryUnresolved> unresolvedList = new ArrayList<>();
            for (int i = 0; i < updatedUnresolved.size(); i++) {
                JSONObject unresolved = updatedUnresolved.getJSONObject(i);
                MemoryUnresolved memoryUnresolved = new MemoryUnresolved();
                memoryUnresolved.setSessionId(sessionIdStr);
                memoryUnresolved.setContent(unresolved.getStr("content"));
                memoryUnresolved.setType(unresolved.getStr("type"));
                memoryUnresolved.setStatus(unresolved.getStr("status"));
                memoryUnresolved.setConsolidationSeq(consolidationSeq);
                unresolvedList.add(memoryUnresolved);
            }
            memoryUnresolvedService.saveBatch(unresolvedList);
            log.info("保存updatedUnresolved成功, 数量:{}", unresolvedList.size());
        }

        // 5. 更新resolvedItems对应的memory_unresolved状态
        JSONArray resolvedItems = summaryData.getJSONArray("resolvedItems");
        if (resolvedItems != null && !resolvedItems.isEmpty()) {
            List<String> resolvedContents = new ArrayList<>();
            for (int i = 0; i < resolvedItems.size(); i++) {
                resolvedContents.add(resolvedItems.getStr(i));
            }
            LambdaUpdateWrapper<MemoryUnresolved> unresolvedWrapper = new LambdaUpdateWrapper<>();
            unresolvedWrapper.eq(MemoryUnresolved::getSessionId, sessionIdStr)
                    .in(MemoryUnresolved::getContent, resolvedContents)
                    .set(MemoryUnresolved::getStatus, "superseded");
            memoryUnresolvedService.update(unresolvedWrapper);
            log.info("更新resolvedItems成功, 数量:{}", resolvedContents.size());
        }

        // 6. 更新ai_session的briefSummary
        String briefSummary = summaryData.getStr("briefSummary");
        if (briefSummary != null && !briefSummary.isEmpty()) {
            LambdaUpdateWrapper<AiSession> sessionWrapper = new LambdaUpdateWrapper<>();
            sessionWrapper.eq(AiSession::getId, sessionId)
                    .set(AiSession::getSummary, briefSummary);
            aiSessionService.update(sessionWrapper);
            log.info("更新ai_session.summary成功, sessionId:{}", sessionIdStr);
        }

        // 7. 标记已整合的消息
        if (!needIntegrationMemory.isEmpty()) {
            List<Long> messageIds = needIntegrationMemory.stream()
                    .map(AiMessage::getId)
                    .collect(Collectors.toList());
            LambdaUpdateWrapper<AiMessage> msgWrapper = new LambdaUpdateWrapper<>();
            msgWrapper.in(AiMessage::getId, messageIds)
                    .set(AiMessage::getConsolidated, 1);
            aiMessageService.update(msgWrapper);
            log.info("标记消息为已整合成功, 数量:{}", messageIds.size());
        }
    }

    private int getNextConsolidationSeq(String sessionId) {
        LambdaQueryWrapper<MemoryPreference> prefQuery = new LambdaQueryWrapper<>();
        prefQuery.eq(MemoryPreference::getSessionId, sessionId)
                .orderByDesc(MemoryPreference::getConsolidationSeq)
                .last("LIMIT 1");
        MemoryPreference lastPref = memoryPreferenceService.getOne(prefQuery);
        int maxSeq = 0;
        if (lastPref != null && lastPref.getConsolidationSeq() != null) {
            maxSeq = lastPref.getConsolidationSeq();
        }
        LambdaQueryWrapper<MemoryUnresolved> unresolvedQuery = new LambdaQueryWrapper<>();
        unresolvedQuery.eq(MemoryUnresolved::getSessionId, sessionId)
                .orderByDesc(MemoryUnresolved::getConsolidationSeq)
                .last("LIMIT 1");
        MemoryUnresolved lastUnresolved = memoryUnresolvedService.getOne(unresolvedQuery);
        if (lastUnresolved != null && lastUnresolved.getConsolidationSeq() != null) {
            maxSeq = Math.max(maxSeq, lastUnresolved.getConsolidationSeq());
        }
        return maxSeq + 1;
    }

    private @NonNull MemoryIntegrationParametersVO  getMemoryIntegrationParametersVO(Long sessionId, Long userId, List<AiMessage> needIntegrationMemory) {
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
