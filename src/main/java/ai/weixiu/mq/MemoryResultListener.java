package ai.weixiu.mq;

import ai.weixiu.common.RedisKey;
import ai.weixiu.config.RabbitMQConfig;
import ai.weixiu.entity.*;
import ai.weixiu.enumerate.PreferenceCategoryEnum;
import ai.weixiu.service.*;
import ai.weixiu.service.ManualRecommendService;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.rabbitmq.client.Channel;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@AllArgsConstructor
@Slf4j
public class MemoryResultListener {

    private final MemoryFactService memoryFactService;
    private final MemoryPreferenceService memoryPreferenceService;
    private final MemoryUnresolvedService memoryUnresolvedService;
    private final AiSessionService aiSessionService;
    private final AiMessageService aiMessageService;
    private final ManualRecommendService manualRecommendService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final WebClient webClient;

    @RabbitListener(queues = RabbitMQConfig.RESULT_QUEUE)
    public void onMemoryResult(Map<String, Object> msg, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
        try {
            String type = (String) msg.get("type");
            boolean success = Boolean.TRUE.equals(msg.get("success"));
            String sessionId = String.valueOf(msg.get("sessionId"));
            Number userIdNum = (Number) msg.get("userId");
            Long userId = userIdNum != null ? userIdNum.longValue() : null;

            if (!success) {
                log.warn("[MQ结果] 任务失败, type={}, sessionId={}, error={}", type, sessionId, msg.get("error"));
                channel.basicAck(tag, false);
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) msg.get("data");
            if (data == null) {
                log.warn("[MQ结果] data为空, type={}, sessionId={}", type, sessionId);
                channel.basicAck(tag, false);
                return;
            }

            if ("realtime_update".equals(type)) {
                Number currentRoundNum = (Number) msg.get("currentRound");
                Integer currentRound = currentRoundNum != null ? currentRoundNum.intValue() : null;
                processRealtimeResult(data, sessionId, userId, currentRound);
            } else if ("consolidation".equals(type)) {
                processConsolidationResult(data, sessionId, userId);
            } else {
                log.warn("[MQ结果] 未知type: {}", type);
            }

            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("[MQ结果] 处理失败: {}", e.getMessage(), e);
            channel.basicNack(tag, false, false);
        }
    }

    private void processRealtimeResult(Map<String, Object> data, String sessionId, Long userId, Integer currentRound) {
        boolean hasUpdate = Boolean.TRUE.equals(data.get("has_update"));
        if (!hasUpdate) {
            log.debug("[MQ结果] 实时更新无变更, 会话ID:{}", sessionId);
            return;
        }

        JSONObject result = JSONUtil.parseObj(data);

        // 标记旧事实为superseded
        JSONArray supersededFactIds = result.getJSONArray("superseded_fact_ids");
        if (supersededFactIds != null && !supersededFactIds.isEmpty()) {
            List<String> factIds = new ArrayList<>();
            for (int i = 0; i < supersededFactIds.size(); i++) {
                factIds.add(supersededFactIds.getStr(i));
            }
            LambdaUpdateWrapper<MemoryFact> factWrapper = new LambdaUpdateWrapper<>();
            factWrapper.in(MemoryFact::getFactId, factIds)
                    .set(MemoryFact::getStatus, "superseded")
                    .set(MemoryFact::getSupersededAt, LocalDateTime.now());
            memoryFactService.update(factWrapper);
            log.info("[MQ结果] 标记旧事实为已替代, 数量:{}", factIds.size());
        }

        // 保存纠正事实（合并旧事实的sourceSeqRange + 当前纠正轮次）
        JSONArray factCorrections = result.getJSONArray("fact_corrections");
        JSONArray newFactIds = result.getJSONArray("new_fact_ids");
        JSONArray oldSeqRanges = result.getJSONArray("old_seq_ranges");
        if (factCorrections != null && !factCorrections.isEmpty()) {
            List<MemoryFact> newFacts = new ArrayList<>();
            for (int i = 0; i < factCorrections.size(); i++) {
                JSONObject correction = factCorrections.getJSONObject(i);
                MemoryFact memoryFact = new MemoryFact();
                memoryFact.setSessionId(sessionId);
                memoryFact.setUserId(userId);
                String vectorDocId = (newFactIds != null && i < newFactIds.size()) ? newFactIds.getStr(i) : null;
                memoryFact.setFactId((vectorDocId != null && !vectorDocId.isEmpty()) ? vectorDocId : UUID.randomUUID().toString());
                memoryFact.setContent(correction.getStr("correct_content"));
                memoryFact.setKeywords(correction.getStr("keywords"));
                memoryFact.setStatus("active");
                memoryFact.setImportance(7); // 纠正事实默认较高重要度
                memoryFact.setConfidence(0.95); // 用户主动纠正，置信度高
                memoryFact.setUsageCount(0);

                // 合并 sourceSeqRange：旧事实的范围 + 当前纠正轮次
                String oldRange = (oldSeqRanges != null && i < oldSeqRanges.size()) ? oldSeqRanges.getStr(i) : "";
                memoryFact.setSourceSeqRange(mergeSeqRange(oldRange, currentRound));

                newFacts.add(memoryFact);
            }
            memoryFactService.saveBatch(newFacts);
            log.info("[MQ结果] 保存纠正事实, 数量:{}", newFacts.size());
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
                    LambdaQueryWrapper<MemoryPreference> deleteQuery = new LambdaQueryWrapper<>();
                    deleteQuery.eq(MemoryPreference::getUserId, userId)
                            .like(MemoryPreference::getContent, content);
                    if (preferenceCategory == PreferenceCategoryEnum.SESSION_PREFERENCE.getCategory()) {
                        deleteQuery.eq(MemoryPreference::getSessionId, sessionId);
                    }
                    List<MemoryPreference> toDelete = memoryPreferenceService.list(deleteQuery);
                    if (!toDelete.isEmpty()) {
                        List<Long> deleteIds = toDelete.stream().map(MemoryPreference::getId).collect(Collectors.toList());
                        memoryPreferenceService.removeByIds(deleteIds);
                        log.info("[MQ结果] 删除偏好, 数量:{}", deleteIds.size());
                        cacheInvalidated = true;
                    }
                } else {
                    LambdaQueryWrapper<MemoryPreference> existQuery = new LambdaQueryWrapper<>();
                    existQuery.eq(MemoryPreference::getUserId, userId)
                            .eq(MemoryPreference::getCategory, category)
                            .eq(MemoryPreference::getPreferenceCategory, preferenceCategory);
                    if (preferenceCategory == PreferenceCategoryEnum.SESSION_PREFERENCE.getCategory()) {
                        existQuery.eq(MemoryPreference::getSessionId, sessionId);
                    }
                    MemoryPreference existing = memoryPreferenceService.getOne(existQuery, false);
                    if (existing != null) {
                        existing.setContent(content);
                        memoryPreferenceService.updateById(existing);
                    } else {
                        MemoryPreference newPref = new MemoryPreference();
                        newPref.setSessionId(sessionId);
                        newPref.setUserId(userId);
                        newPref.setContent(content);
                        newPref.setCategory(category);
                        newPref.setPreferenceCategory(preferenceCategory);
                        newPref.setConsolidationSeq(0);
                        memoryPreferenceService.save(newPref);
                    }
                    cacheInvalidated = true;
                }
            }
            if (cacheInvalidated) {
                // 清除用户级偏好缓存（所有会话共享）
                redisTemplate.delete(RedisKey.PREFERENCE_CACHE_USER + userId);
                // 清除当前会话级偏好缓存
                redisTemplate.delete(RedisKey.PREFERENCE_CACHE_SESSION + userId + ":" + sessionId);
                // 偏好变更后清除个性化推荐缓存，下次访问时重新计算
                if (userId != null) {
                    manualRecommendService.invalidateCache(userId);
                }
            }
        }

        log.info("[MQ结果] 实时记忆更新完成, 会话ID:{}", sessionId);
    }

    private void processConsolidationResult(Map<String, Object> data, String sessionId, Long userId) {
        JSONObject summaryData = JSONUtil.parseObj(data);

        int consolidationSeq = getNextConsolidationSeq(sessionId);

        // 1. 保存newFacts
        JSONArray factIds = summaryData.getJSONArray("fact_ids");
        JSONArray newFacts = summaryData.getJSONArray("newFacts");
        if (newFacts != null && !newFacts.isEmpty()) {
            List<MemoryFact> facts = new ArrayList<>();
            for (int i = 0; i < newFacts.size(); i++) {
                JSONObject fact = newFacts.getJSONObject(i);
                MemoryFact memoryFact = new MemoryFact();
                memoryFact.setSessionId(sessionId);
                memoryFact.setUserId(userId);
                String vectorDocId = (factIds != null && i < factIds.size()) ? factIds.getStr(i) : null;
                memoryFact.setFactId((vectorDocId != null && !vectorDocId.isEmpty()) ? vectorDocId : UUID.randomUUID().toString());
                memoryFact.setContent(fact.getStr("content"));
                memoryFact.setKeywords(fact.getStr("keywords"));
                memoryFact.setSourceSeqRange(fact.getStr("sourceSeqRange"));
                memoryFact.setStatus("active");
                memoryFact.setImportance(fact.getInt("importance", 5));
                memoryFact.setConfidence(fact.getDouble("confidence", 0.80));
                memoryFact.setUsageCount(0);
                // 业务维度（Phase 4）
                String deviceType = fact.getStr("deviceType", "");
                if (!deviceType.isEmpty()) {
                    memoryFact.setDeviceType(deviceType);
                }
                String equipmentIdStr = fact.getStr("equipmentId", "");
                if (!equipmentIdStr.isEmpty()) {
                    try { memoryFact.setEquipmentId(Long.valueOf(equipmentIdStr)); } catch (NumberFormatException ignored) {}
                }
                String siteIdStr = fact.getStr("siteId", "");
                if (!siteIdStr.isEmpty()) {
                    try { memoryFact.setSiteId(Long.valueOf(siteIdStr)); } catch (NumberFormatException ignored) {}
                }
                String taskIdStr = fact.getStr("taskId", "");
                if (!taskIdStr.isEmpty()) {
                    try { memoryFact.setTaskId(Long.valueOf(taskIdStr)); } catch (NumberFormatException ignored) {}
                }
                facts.add(memoryFact);
            }
            memoryFactService.saveBatch(facts);
            log.info("[MQ结果] 保存新事实, 数量:{}", facts.size());
        }

        // 2. 更新supersededIds
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
            log.info("[MQ结果] 更新已替代事实, 数量:{}", supersededFactIds.size());

            // 同步通知 Python 删除 Redis 向量库中的旧事实
            try {
                Map<String, Object> deleteRequest = new HashMap<>();
                deleteRequest.put("fact_ids", supersededFactIds);
                webClient.post()
                        .uri("/ai/memory/delete_facts")
                        .bodyValue(deleteRequest)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();
                log.info("[MQ结果] 已通知Python删除旧事实向量, 数量:{}", supersededFactIds.size());
            } catch (Exception e) {
                log.warn("[MQ结果] 通知Python删除旧事实向量失败（不影响主流程）: {}", e.getMessage());
            }
        }

        // 3. 保存updatedPreferences
        JSONArray updatedPreferences = summaryData.getJSONArray("updatedPreferences");
        if (updatedPreferences != null && !updatedPreferences.isEmpty()) {
            List<MemoryPreference> preferences = new ArrayList<>();
            for (int i = 0; i < updatedPreferences.size(); i++) {
                JSONObject pref = updatedPreferences.getJSONObject(i);
                String sourceType = pref.getStr("sourceType", "inferred");
                String action = pref.getStr("action", "upsert");

                if ("explicit".equals(sourceType) && "upsert".equals(action)) {
                    String category = pref.getStr("category");
                    Integer preferenceCategory = pref.getInt("preferenceCategory", 0);
                    LambdaQueryWrapper<MemoryPreference> existQuery = new LambdaQueryWrapper<>();
                    existQuery.eq(MemoryPreference::getUserId, userId)
                            .eq(MemoryPreference::getCategory, category)
                            .eq(MemoryPreference::getPreferenceCategory, preferenceCategory);
                    if (preferenceCategory == PreferenceCategoryEnum.SESSION_PREFERENCE.getCategory()) {
                        existQuery.eq(MemoryPreference::getSessionId, sessionId);
                    }
                    MemoryPreference existing = memoryPreferenceService.getOne(existQuery, false);
                    if (existing != null) {
                        existing.setContent(pref.getStr("content"));
                        existing.setConsolidationSeq(consolidationSeq);
                        memoryPreferenceService.updateById(existing);
                    } else {
                        MemoryPreference mp = new MemoryPreference();
                        mp.setSessionId(sessionId);
                        mp.setUserId(userId);
                        mp.setContent(pref.getStr("content"));
                        mp.setCategory(category);
                        mp.setPreferenceCategory(preferenceCategory);
                        mp.setConsolidationSeq(consolidationSeq);
                        preferences.add(mp);
                    }
                } else {
                    MemoryPreference mp = new MemoryPreference();
                    mp.setSessionId(sessionId);
                    mp.setUserId(userId);
                    mp.setContent(pref.getStr("content"));
                    mp.setCategory(pref.getStr("category"));
                    mp.setPreferenceCategory(pref.getInt("preferenceCategory", 0));
                    mp.setConsolidationSeq(consolidationSeq);
                    preferences.add(mp);
                }
            }
            if (!preferences.isEmpty()) {
                memoryPreferenceService.saveBatch(preferences);
            }
            // 清除用户级偏好缓存（所有会话共享）
            redisTemplate.delete(RedisKey.PREFERENCE_CACHE_USER + userId);
            // 清除当前会话级偏好缓存
            redisTemplate.delete(RedisKey.PREFERENCE_CACHE_SESSION + userId + ":" + sessionId);
            // 整合产生偏好变更后清除个性化推荐缓存
            if (userId != null) {
                manualRecommendService.invalidateCache(userId);
            }
            log.info("[MQ结果] 保存偏好, 数量:{}", updatedPreferences.size());
        }

        // 4. 保存updatedUnresolved
        JSONArray updatedUnresolved = summaryData.getJSONArray("updatedUnresolved");
        if (updatedUnresolved != null && !updatedUnresolved.isEmpty()) {
            List<MemoryUnresolved> unresolvedList = new ArrayList<>();
            for (int i = 0; i < updatedUnresolved.size(); i++) {
                JSONObject unresolved = updatedUnresolved.getJSONObject(i);
                MemoryUnresolved mu = new MemoryUnresolved();
                mu.setSessionId(sessionId);
                mu.setContent(unresolved.getStr("content"));
                mu.setType(unresolved.getStr("type"));
                mu.setStatus(unresolved.getStr("status"));
                mu.setConsolidationSeq(consolidationSeq);
                unresolvedList.add(mu);
            }
            memoryUnresolvedService.saveBatch(unresolvedList);
            log.info("[MQ结果] 保存待办, 数量:{}", unresolvedList.size());
        }

        // 5. 更新resolvedItems
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
            log.info("[MQ结果] 更新已解决事项, 数量:{}", resolvedIds.size());
        }

        // 6. 更新briefSummary
        String briefSummary = summaryData.getStr("briefSummary");
        if (briefSummary != null && !briefSummary.isEmpty()) {
            LambdaUpdateWrapper<AiSession> sessionWrapper = new LambdaUpdateWrapper<>();
            sessionWrapper.eq(AiSession::getId, Long.valueOf(sessionId))
                    .set(AiSession::getSummary, briefSummary);
            aiSessionService.update(sessionWrapper);
        }

        // 7. 标记已整合的消息
        @SuppressWarnings("unchecked")
        List<Number> messageIdNums = (List<Number>) data.get("consolidatedMessageIds");
        if (messageIdNums != null && !messageIdNums.isEmpty()) {
            List<Long> messageIds = messageIdNums.stream().map(Number::longValue).collect(Collectors.toList());
            LambdaUpdateWrapper<AiMessage> msgWrapper = new LambdaUpdateWrapper<>();
            msgWrapper.in(AiMessage::getId, messageIds)
                    .set(AiMessage::getConsolidated, 1);
            aiMessageService.update(msgWrapper);
            log.info("[MQ结果] 标记消息为已整合, 数量:{}", messageIds.size());
        }

        log.info("[MQ结果] 记忆整合完成, 会话ID:{}", sessionId);
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

    /**
     * 合并旧事实的 sourceSeqRange 和当前纠正轮次
     *
     * 示例：
     *   oldRange="3-5", currentRound=9  → "3-5,9"
     *   oldRange="",    currentRound=9  → "9"
     *   oldRange="3-5", currentRound=null → "3-5"
     *   oldRange=null,  currentRound=null → null
     */
    private String mergeSeqRange(String oldRange, Integer currentRound) {
        boolean hasOld = oldRange != null && !oldRange.isBlank();
        boolean hasNew = currentRound != null;

        if (hasOld && hasNew) {
            return oldRange + "," + currentRound;
        } else if (hasOld) {
            return oldRange;
        } else if (hasNew) {
            return String.valueOf(currentRound);
        }
        return null;
    }
}
