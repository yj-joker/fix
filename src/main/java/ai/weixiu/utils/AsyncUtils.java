package ai.weixiu.utils;

import ai.weixiu.common.RedisKey;
import ai.weixiu.entity.AiMessage;
import ai.weixiu.entity.AiSession;
import ai.weixiu.entity.CachedPreferences;
import ai.weixiu.entity.MemoryFact;
import ai.weixiu.entity.MemoryMessage;
import ai.weixiu.entity.MemoryPreference;
import ai.weixiu.entity.MemoryUnresolved;
import ai.weixiu.enumerate.PreferenceCategoryEnum;
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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
    private RedisTemplate<String, Object> redisTemplate;

    private static final int MAX_CONSOLIDATION_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 3000;


    //异步整合记忆
    @Async
    public void integrationMemory(Integer roundCount, Long sessionId, Long userId, Integer maxMemory) {
        String lockKey = RedisKey.CONSOLIDATION_LOCK + sessionId;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", 5, TimeUnit.MINUTES);
        if (acquired == null || !acquired) {
            log.warn("记忆整合已在进行中, sessionId:{}", sessionId);
            return;
        }

        try {
            List<AiMessage> needIntegrationMemory = aiMessageService.getNeedIntegrationMemory(roundCount, sessionId, userId, maxMemory);
            if (needIntegrationMemory.isEmpty()) {
                log.info("没有需要整合的消息, sessionId:{}", sessionId);
                return;
            }

            MemoryIntegrationParametersVO memoryIntegrationParameters = getMemoryIntegrationParametersVO(sessionId, userId, needIntegrationMemory);
            String memoryIntegrationParametersToString = JSONUtil.toJsonStr(memoryIntegrationParameters);
            log.info("此次用户整合记忆发送:{}", memoryIntegrationParametersToString);

            WebClient webClient = WebClient.builder()
                    .baseUrl("http://127.0.0.1:8000")
                    .defaultHeader("Content-Type", "application/json")
                    .build();

            String summary = null;
            for (int attempt = 0; attempt <= MAX_CONSOLIDATION_RETRIES; attempt++) {
                if (attempt > 0) {
                    log.info("记忆整合重试第{}次, sessionId:{}", attempt, sessionId);
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }

                //调用模型,获取模型生成的摘要
                String response;
                try {
                    response = webClient.post()
                            .uri("/ai/memory/consolidate")
                            .bodyValue(memoryIntegrationParametersToString)
                            .retrieve()
                            .bodyToMono(String.class)
                            .block();
                } catch (Exception e) {
                    log.warn("记忆整合HTTP调用失败, attempt:{}, sessionId:{}, error:{}", attempt, sessionId, e.getMessage());
                    continue;
                }

                // 检查返回是否有效
                if (response != null) {
                    try {
                        JSONObject responseJson = JSONUtil.parseObj(response);
                        if (responseJson.getJSONObject("summary") != null) {
                            summary = response;
                            break;
                        }
                        // 检查是否返回了错误状态
                        String status = responseJson.getStr("status");
                        if ("error".equals(status)) {
                            log.warn("记忆整合返回错误, attempt:{}, sessionId:{}, response:{}", attempt, sessionId, response);
                            continue;
                        }
                        summary = response;
                        break;
                    } catch (Exception e) {
                        log.warn("记忆整合返回解析失败, attempt:{}, sessionId:{}, response:{}", attempt, sessionId, response);
                    }
                }
            }

            if (summary == null) {
                log.error("记忆整合最终失败(重试{}次后), sessionId:{}", MAX_CONSOLIDATION_RETRIES, sessionId);
                return;
            }

            //保存模型生成的摘要
            log.info("模型返回的摘要, sessionId:{}", summary);
            saveSummary(summary, sessionId, userId, needIntegrationMemory);
        } finally {
            redisTemplate.delete(lockKey);
        }
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
        // Python向量库存储事实时生成的doc_id列表，与newFacts一一对应
        // 用这些doc_id作为MySQL的factId，确保两端ID一致（实时纠正时能正确匹配）
        JSONArray factIds = summaryData.getJSONArray("fact_ids");
        JSONArray newFacts = summaryData.getJSONArray("newFacts");
        if (newFacts != null && !newFacts.isEmpty()) {
            List<MemoryFact> facts = new ArrayList<>();
            for (int i = 0; i < newFacts.size(); i++) {
                JSONObject fact = newFacts.getJSONObject(i);
                MemoryFact memoryFact = new MemoryFact();
                memoryFact.setSessionId(sessionIdStr);
                memoryFact.setUserId(userId);
                // 优先使用Python返回的向量库doc_id，如果没有则回退到UUID
                String vectorDocId = (factIds != null && i < factIds.size()) ? factIds.getStr(i) : null;
                memoryFact.setFactId((vectorDocId != null && !vectorDocId.isEmpty()) ? vectorDocId : UUID.randomUUID().toString());
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
            List<String> supersededFactIds = new ArrayList<>();
            for (int i = 0; i < supersededIds.size(); i++) {
                supersededFactIds.add(supersededIds.getStr(i));
            }
            LambdaUpdateWrapper<MemoryFact> factWrapper = new LambdaUpdateWrapper<>();
            factWrapper.in(MemoryFact::getFactId, supersededFactIds)
                    .set(MemoryFact::getStatus, "superseded")
                    .set(MemoryFact::getSupersededAt, LocalDateTime.now());
            memoryFactService.update(factWrapper);
            log.info("更新supersededIds成功, 数量:{}", supersededFactIds.size());
        }

        // 3. 保存updatedPreferences到memory_preference
        JSONArray updatedPreferences = summaryData.getJSONArray("updatedPreferences");
        if (updatedPreferences != null && !updatedPreferences.isEmpty()) {
            List<MemoryPreference> preferences = new ArrayList<>();
            for (int i = 0; i < updatedPreferences.size(); i++) {
                JSONObject pref = updatedPreferences.getJSONObject(i);
                String sourceType = pref.getStr("sourceType", "inferred");
                String action = pref.getStr("action", "upsert");

                if ("explicit".equals(sourceType) && "upsert".equals(action)) {
                    // 显式偏好：检查是否已存在同类偏好，存在则更新
                    String category = pref.getStr("category");
                    Integer preferenceCategory = pref.getInt("preferenceCategory", 0);
                    LambdaQueryWrapper<MemoryPreference> existQuery = new LambdaQueryWrapper<>();
                    existQuery.eq(MemoryPreference::getUserId, userId)
                            .eq(MemoryPreference::getCategory, category)
                            .eq(MemoryPreference::getPreferenceCategory, preferenceCategory);
                    if (preferenceCategory == PreferenceCategoryEnum.SESSION_PREFERENCE.getCategory()) {
                        existQuery.eq(MemoryPreference::getSessionId, sessionIdStr);
                    }
                    MemoryPreference existing = memoryPreferenceService.getOne(existQuery, false);
                    if (existing != null) {
                        existing.setContent(pref.getStr("content"));
                        existing.setConsolidationSeq(consolidationSeq);
                        memoryPreferenceService.updateById(existing);
                    } else {
                        MemoryPreference memoryPreference = new MemoryPreference();
                        memoryPreference.setSessionId(sessionIdStr);
                        memoryPreference.setUserId(userId);
                        memoryPreference.setContent(pref.getStr("content"));
                        memoryPreference.setCategory(category);
                        memoryPreference.setPreferenceCategory(preferenceCategory);
                        memoryPreference.setConsolidationSeq(consolidationSeq);
                        preferences.add(memoryPreference);
                    }
                } else {
                    // 推断偏好或新增：直接新建
                    MemoryPreference memoryPreference = new MemoryPreference();
                    memoryPreference.setSessionId(sessionIdStr);
                    memoryPreference.setUserId(userId);
                    memoryPreference.setContent(pref.getStr("content"));
                    memoryPreference.setCategory(pref.getStr("category"));
                    memoryPreference.setPreferenceCategory(pref.getInt("preferenceCategory", 0));
                    memoryPreference.setConsolidationSeq(consolidationSeq);
                    preferences.add(memoryPreference);
                }
            }
            if (!preferences.isEmpty()) {
                memoryPreferenceService.saveBatch(preferences);
            }
            log.info("保存updatedPreferences成功, 数量:{}", updatedPreferences.size());
            // 保存成功后删除缓存
            String prefCacheKey = RedisKey.PREFERENCE_CACHE + userId + ":" + sessionIdStr;
            redisTemplate.delete(prefCacheKey);
            log.info("删除偏好缓存, key:{}", prefCacheKey);
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

        // 5. 更新resolvedItems对应的memory_unresolved状态（通过ID）
        JSONArray resolvedItems = summaryData.getJSONArray("resolvedItems");
        if (resolvedItems != null && !resolvedItems.isEmpty()) {
            List<Long> resolvedIds = new ArrayList<>();
            for (int i = 0; i < resolvedItems.size(); i++) {
                resolvedIds.add(resolvedItems.getLong(i));
            }
            LambdaUpdateWrapper<MemoryUnresolved> unresolvedWrapper = new LambdaUpdateWrapper<>();
            unresolvedWrapper.in(MemoryUnresolved::getId, resolvedIds)
                    .set(MemoryUnresolved::getStatus, "resolved");
            memoryUnresolvedService.update(unresolvedWrapper);
            log.info("更新resolvedItems成功, 数量:{}", resolvedIds.size());
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

    private @NonNull MemoryIntegrationParametersVO getMemoryIntegrationParametersVO(Long sessionId, Long userId, List<AiMessage> needIntegrationMemory) {
        List<MemoryMessage> memoryMessages = new ArrayList<>();
        for (AiMessage msg : needIntegrationMemory) {
            MemoryMessage memoryMessage = new MemoryMessage();
            memoryMessage.setRole(msg.getRole());
            memoryMessage.setContent(msg.getContent());
            memoryMessages.add(memoryMessage);
        }
        //获取长期偏好记忆 用户级加当前会话级（先查缓存）
        String prefCacheKey = RedisKey.PREFERENCE_CACHE + userId + ":" + sessionId;
        CachedPreferences cached = (CachedPreferences) redisTemplate.opsForValue().get(prefCacheKey);
        List<MemoryPreference> preference;
        if (cached != null) {
            preference = new ArrayList<>();
            if (cached.getUserPreferences() != null) {
                preference.addAll(cached.getUserPreferences());
            }
            if (cached.getSessionPreferences() != null) {
                preference.addAll(cached.getSessionPreferences());
            }
        } else {
            preference = memoryPreferenceService.getPreference(sessionId, userId);
            if (!preference.isEmpty()) {
                List<MemoryPreference> userPrefs = new ArrayList<>();
                List<MemoryPreference> sessionPrefs = new ArrayList<>();
                for (MemoryPreference p : preference) {
                    if (p.getPreferenceCategory() != null
                            && p.getPreferenceCategory() == PreferenceCategoryEnum.USER_PREFERENCE.getCategory()) {
                        userPrefs.add(p);
                    } else {
                        sessionPrefs.add(p);
                    }
                }
                CachedPreferences toCache = new CachedPreferences(userPrefs, sessionPrefs);
                redisTemplate.opsForValue().set(prefCacheKey, toCache, 5, TimeUnit.HOURS);
            }
        }
        List<MemoryPreferenceVO> memoryPreferenceVO = getMemoryPreferenceVO(preference);
        //获取未完成摘要
        List<MemoryUnresolved> memoryUnresolved = memoryUnresolvedService.getUnresolved(sessionId);
        List<MemoryUnresolvedVO> memoryUnresolvedVO = getMemoryUnresolvedVO(memoryUnresolved);
        //获取上一次摘要
        LambdaQueryWrapper<AiSession> sessionQuery = new LambdaQueryWrapper<>();
        sessionQuery.eq(AiSession::getId, sessionId);
        AiSession session = aiSessionService.getOne(sessionQuery);
        String previousSummary = session != null ? session.getSummary() : null;

        MemoryIntegrationParametersVO memoryIntegrationParameters = new MemoryIntegrationParametersVO();
        memoryIntegrationParameters.setSessionId(sessionId.toString());
        memoryIntegrationParameters.setMemoryMessages(memoryMessages);
        memoryIntegrationParameters.setMemoryPreferenceVOList(memoryPreferenceVO);
        memoryIntegrationParameters.setMemoryUnresolvedVOList(memoryUnresolvedVO);
        memoryIntegrationParameters.setPreviousSummary(previousSummary);
        return memoryIntegrationParameters;
    }

    private List<MemoryUnresolvedVO> getMemoryUnresolvedVO(List<MemoryUnresolved> memoryUnresolved) {
        List<MemoryUnresolvedVO> memoryUnresolvedVOList = new ArrayList<>();
        for (MemoryUnresolved item : memoryUnresolved) {
            MemoryUnresolvedVO memoryUnresolvedVO = new MemoryUnresolvedVO();
            memoryUnresolvedVO.setId(item.getId());
            memoryUnresolvedVO.setContent(item.getContent());
            memoryUnresolvedVO.setType(item.getType());
            memoryUnresolvedVO.setStatus(item.getStatus());
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

    /**
     * 实时记忆更新
     * 每轮对话完成后异步调用，检测事实纠正和偏好变更并立即生效
     */
    @Async
    public void realtimeMemoryUpdate(Long sessionId, Long userId, String userMessage, String aiResponse) {
        try {
            JSONObject requestBody = new JSONObject();
            requestBody.set("session_id", sessionId.toString());
            requestBody.set("user_message", userMessage);
            requestBody.set("ai_response", aiResponse);

            WebClient webClient = WebClient.builder()
                    .baseUrl("http://127.0.0.1:8000")
                    .defaultHeader("Content-Type", "application/json")
                    .build();

            String response = webClient.post()
                    .uri("/ai/memory/realtime_update")
                    .bodyValue(requestBody.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (response == null) {
                log.warn("[realtime] 实时更新返回为空, sessionId:{}", sessionId);
                return;
            }

            log.info("[realtime] Python返回原始响应: {}", response);
            JSONObject result = JSONUtil.parseObj(response);
            if (!result.getBool("has_update", false)) {
                log.debug("[realtime] 无需实时更新, sessionId:{}", sessionId);
                return;
            }
            log.info("[realtime] 检测到实时更新, sessionId:{}", sessionId);

            String sessionIdStr = sessionId.toString();

            // 处理事实纠正 - 标记旧事实为superseded
            JSONArray supersededFactIds = result.getJSONArray("superseded_fact_ids");
            if (supersededFactIds != null && !supersededFactIds.isEmpty()) {
                List<String> factIds = new ArrayList<>();
                for (int i = 0; i < supersededFactIds.size(); i++) {
                    factIds.add(supersededFactIds.getStr(i));
                }
                // 在MySQL中标记旧事实为superseded
                LambdaUpdateWrapper<MemoryFact> factWrapper = new LambdaUpdateWrapper<>();
                factWrapper.in(MemoryFact::getFactId, factIds)
                        .set(MemoryFact::getStatus, "superseded")
                        .set(MemoryFact::getSupersededAt, LocalDateTime.now());
                memoryFactService.update(factWrapper);
                log.info("[realtime] 标记旧事实为superseded, 数量:{}", factIds.size());
            }

            // 保存新的正确事实（使用Python返回的向量库doc_id作为factId）
            JSONArray factCorrections = result.getJSONArray("fact_corrections");
            JSONArray newFactIds = result.getJSONArray("new_fact_ids");
            if (factCorrections != null && !factCorrections.isEmpty()) {
                List<MemoryFact> newFacts = new ArrayList<>();
                for (int i = 0; i < factCorrections.size(); i++) {
                    JSONObject correction = factCorrections.getJSONObject(i);
                    MemoryFact memoryFact = new MemoryFact();
                    memoryFact.setSessionId(sessionIdStr);
                    memoryFact.setUserId(userId);
                    // 使用Python返回的向量库doc_id，确保MySQL和向量库ID一致
                    String vectorDocId = (newFactIds != null && i < newFactIds.size()) ? newFactIds.getStr(i) : null;
                    memoryFact.setFactId((vectorDocId != null && !vectorDocId.isEmpty()) ? vectorDocId : UUID.randomUUID().toString());
                    memoryFact.setContent(correction.getStr("correct_content"));
                    memoryFact.setKeywords(correction.getStr("keywords"));
                    memoryFact.setStatus("active");
                    newFacts.add(memoryFact);
                }
                memoryFactService.saveBatch(newFacts);
                log.info("[realtime] 保存纠正事实成功, 数量:{}", newFacts.size());
            }

            // 处理偏好变更
            JSONArray preferenceChanges = result.getJSONArray("preference_changes");
            if (preferenceChanges != null && !preferenceChanges.isEmpty()) {
                boolean cacheInvalidated = false;
                for (int i = 0; i < preferenceChanges.size(); i++) {
                    JSONObject prefChange = preferenceChanges.getJSONObject(i);
                    String action = prefChange.getStr("action", "upsert");
                    String content = prefChange.getStr("content");
                    String category = prefChange.getStr("category", "其他");
                    Integer preferenceCategory = prefChange.getInt("preferenceCategory", 0);

                    if ("delete".equals(action)) {
                        // 删除偏好：根据内容模糊匹配找到并物理删除
                        LambdaQueryWrapper<MemoryPreference> deleteQuery = new LambdaQueryWrapper<>();
                        deleteQuery.eq(MemoryPreference::getUserId, userId)
                                .like(MemoryPreference::getContent, content);
                        if (preferenceCategory == PreferenceCategoryEnum.SESSION_PREFERENCE.getCategory()) {
                            deleteQuery.eq(MemoryPreference::getSessionId, sessionIdStr);
                        }
                        List<MemoryPreference> toDelete = memoryPreferenceService.list(deleteQuery);
                        if (!toDelete.isEmpty()) {
                            List<Long> deleteIds = toDelete.stream()
                                    .map(MemoryPreference::getId)
                                    .collect(Collectors.toList());
                            memoryPreferenceService.removeByIds(deleteIds);
                            log.info("[realtime] 删除偏好成功, 数量:{}, content:{}", deleteIds.size(), content);
                            cacheInvalidated = true;
                        }
                    } else {
                        // upsert偏好：查找已有同类偏好，存在则更新，不存在则新建
                        LambdaQueryWrapper<MemoryPreference> existQuery = new LambdaQueryWrapper<>();
                        existQuery.eq(MemoryPreference::getUserId, userId)
                                .eq(MemoryPreference::getCategory, category)
                                .eq(MemoryPreference::getPreferenceCategory, preferenceCategory);
                        if (preferenceCategory == PreferenceCategoryEnum.SESSION_PREFERENCE.getCategory()) {
                            existQuery.eq(MemoryPreference::getSessionId, sessionIdStr);
                        }
                        MemoryPreference existing = memoryPreferenceService.getOne(existQuery, false);
                        if (existing != null) {
                            existing.setContent(content);
                            memoryPreferenceService.updateById(existing);
                            log.info("[realtime] 更新偏好成功, id:{}, content:{}", existing.getId(), content);
                        } else {
                            MemoryPreference newPref = new MemoryPreference();
                            newPref.setSessionId(sessionIdStr);
                            newPref.setUserId(userId);
                            newPref.setContent(content);
                            newPref.setCategory(category);
                            newPref.setPreferenceCategory(preferenceCategory);
                            newPref.setConsolidationSeq(0);
                            memoryPreferenceService.save(newPref);
                            log.info("[realtime] 新增偏好成功, content:{}", content);
                        }
                        cacheInvalidated = true;
                    }
                }
                // 清除偏好缓存
                if (cacheInvalidated) {
                    String prefCacheKey = RedisKey.PREFERENCE_CACHE + userId + ":" + sessionIdStr;
                    redisTemplate.delete(prefCacheKey);
                    log.info("[realtime] 删除偏好缓存, key:{}", prefCacheKey);
                }
            }

            log.info("[realtime] 实时记忆更新完成, sessionId:{}", sessionId);
        } catch (Exception e) {
            log.error("[realtime] 实时记忆更新失败, sessionId:{}, error:{}", sessionId, e.getMessage(), e);
        }
    }
}
