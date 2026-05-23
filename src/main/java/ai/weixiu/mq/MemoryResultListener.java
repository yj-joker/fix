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

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
                processRealtimeResult(data, sessionId, userId);
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

    private void processRealtimeResult(Map<String, Object> data, String sessionId, Long userId) {
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

        // 保存纠正事实
        JSONArray factCorrections = result.getJSONArray("fact_corrections");
        JSONArray newFactIds = result.getJSONArray("new_fact_ids");
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
                String prefCacheKey = RedisKey.PREFERENCE_CACHE + userId + ":" + sessionId;
                redisTemplate.delete(prefCacheKey);
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
            String prefCacheKey = RedisKey.PREFERENCE_CACHE + userId + ":" + sessionId;
            redisTemplate.delete(prefCacheKey);
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
}
