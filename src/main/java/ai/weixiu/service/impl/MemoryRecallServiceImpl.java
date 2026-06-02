package ai.weixiu.service.impl;

import ai.weixiu.common.RedisKey;
import ai.weixiu.entity.AiSession;
import ai.weixiu.entity.MemoryFact;
import ai.weixiu.entity.MemoryPreference;
import ai.weixiu.entity.MemoryRecallTrace;
import ai.weixiu.entity.MemoryReflection;
import ai.weixiu.entity.MemoryUnresolved;
import ai.weixiu.enumerate.PreferenceCategoryEnum;
import ai.weixiu.mapper.MemoryRecallTraceMapper;
import ai.weixiu.pojo.dto.RecallContext;
import ai.weixiu.service.AiSessionService;
import ai.weixiu.service.MemoryFactService;
import ai.weixiu.service.MemoryPreferenceService;
import ai.weixiu.service.MemoryRecallService;
import ai.weixiu.service.MemoryReflectionService;
import ai.weixiu.service.MemoryUnresolvedService;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 记忆召回服务实现
 *
 * <p>从 AiServiceImpl 中抽取的 searchRelevantFacts + loadPreferences + getUnresolved 逻辑，
 * 统一入口、统一出口，并在每次召回后异步记录 trace。</p>
 */
@Service
@AllArgsConstructor
@Slf4j
public class MemoryRecallServiceImpl implements MemoryRecallService {

    private final AiSessionService aiSessionService;
    private final MemoryFactService memoryFactService;
    private final MemoryPreferenceService memoryPreferenceService;
    private final MemoryReflectionService memoryReflectionService;
    private final MemoryUnresolvedService memoryUnresolvedService;
    private final WebClient webClient;
    private final RedisTemplate<String, Object> redisTemplate;
    private final MemoryRecallTraceMapper recallTraceMapper;

    @Override
    public RecallContext recall(Long sessionId, Long userId, String userMessage, Integer roundNo,
                                String deviceType, String equipmentId, String siteId, String taskId) {
        long totalStart = System.currentTimeMillis();
        RecallContext ctx = new RecallContext();

        // ========== 1. 获取上一轮摘要 ==========
        AiSession session = aiSessionService.getById(sessionId);
        ctx.setPreviousSummary(session != null ? session.getSummary() : null);

        // ========== 2. 三个独立查询并行执行 ==========
        long factStart = System.currentTimeMillis();
        CompletableFuture<List<JSONObject>> factsFuture =
                CompletableFuture.supplyAsync(() -> searchRelevantFacts(
                        userMessage, userId, deviceType, equipmentId, siteId, taskId));

        long prefStart = System.currentTimeMillis();
        CompletableFuture<List<MemoryPreference>> preferencesFuture =
                CompletableFuture.supplyAsync(() -> loadPreferences(sessionId, userId));

        CompletableFuture<List<MemoryUnresolved>> unresolvedFuture =
                CompletableFuture.supplyAsync(() -> memoryUnresolvedService.getUnresolved(sessionId));

        CompletableFuture<List<MemoryReflection>> reflectionsFuture =
                CompletableFuture.supplyAsync(() -> memoryReflectionService.getActiveReflections(userId));

        CompletableFuture.allOf(factsFuture, preferencesFuture, unresolvedFuture, reflectionsFuture).join();

        List<JSONObject> relevantFacts = factsFuture.join();
        long factEnd = System.currentTimeMillis();

        List<MemoryPreference> preferences = preferencesFuture.join();
        long prefEnd = System.currentTimeMillis();

        List<MemoryUnresolved> unresolved = unresolvedFuture.join();

        List<MemoryReflection> reflections = reflectionsFuture.join();

        // ========== 3. 填充 RecallContext ==========
        ctx.setRelevantFacts(relevantFacts);
        ctx.setPreferences(preferences);
        ctx.setUnresolvedItems(unresolved);

        // 填充用户画像
        List<Map<String, String>> profileItems = new ArrayList<>();
        for (MemoryReflection r : reflections) {
            Map<String, String> item = new HashMap<>();
            item.put("type", r.getReflectionType());
            item.put("content", r.getContent());
            profileItems.add(item);
        }
        ctx.setUserProfile(profileItems);

        // 提取事实内容（供 MQ recentFacts）
        List<String> factContents = new ArrayList<>();
        List<String> factIds = new ArrayList<>();
        List<Double> factScores = new ArrayList<>();
        for (JSONObject fact : relevantFacts) {
            String content = fact.getStr("content");
            if (content != null && !content.isEmpty()) {
                factContents.add(content);
            }
            factIds.add(fact.getStr("doc_id", ""));
            factScores.add(fact.getDouble("score", 0.0));
        }
        ctx.setRecentFactContents(factContents);
        ctx.setFactIds(factIds);
        ctx.setFactScores(factScores);

        long totalEnd = System.currentTimeMillis();
        ctx.setTotalLatencyMs(totalEnd - totalStart);
        ctx.setFactLatencyMs(factEnd - factStart);
        ctx.setPreferenceLatencyMs(prefEnd - prefStart);

        // ========== 4. 异步保存 Trace ==========
        CompletableFuture.runAsync(() -> saveTrace(sessionId, userId, roundNo, userMessage, ctx));

        // ========== 5. 异步更新事实使用统计 ==========
        if (!factIds.isEmpty()) {
            CompletableFuture.runAsync(() -> updateFactUsage(factIds));
        }

        return ctx;
    }

    // ==================== 从 AiServiceImpl 迁移的私有方法 ====================

    /**
     * 调用 Python 向量检索接口，查找与用户消息最相关的历史事实。
     * 支持传递业务上下文参数（deviceType/equipmentId/siteId/taskId），
     * 让 FactReranker 的 business_match 因子优先返回与当前业务相关的记忆。
     */
    private List<JSONObject> searchRelevantFacts(String userMessage, Long userId,
                                                  String deviceType, String equipmentId,
                                                  String siteId, String taskId) {
        try {
            LambdaQueryWrapper<AiSession> sessionQuery = new LambdaQueryWrapper<>();
            sessionQuery.eq(AiSession::getUserId, userId).select(AiSession::getId);
            List<String> userSessionIds = aiSessionService.list(sessionQuery)
                    .stream().map(s -> s.getId().toString()).toList();

            String response = webClient.post()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/ai/memory/search_facts")
                                .queryParam("query", userMessage)
                                .queryParam("top_k", 5)
                                .queryParam("session_ids", String.join(",", userSessionIds));
                        if (deviceType != null && !deviceType.isEmpty()) {
                            uriBuilder.queryParam("device_type", deviceType);
                        }
                        if (equipmentId != null && !equipmentId.isEmpty()) {
                            uriBuilder.queryParam("equipment_id", equipmentId);
                        }
                        if (siteId != null && !siteId.isEmpty()) {
                            uriBuilder.queryParam("site_id", siteId);
                        }
                        if (taskId != null && !taskId.isEmpty()) {
                            uriBuilder.queryParam("task_id", taskId);
                        }
                        return uriBuilder.build();
                    })
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
            log.warn("事实记忆向量检索失败，降级运行: {}", e.getMessage());
        }
        return new ArrayList<>();
    }

    /**
     * 加载偏好（双级缓存：用户级 + 会话级分开缓存）。
     */
    @SuppressWarnings("unchecked")
    private List<MemoryPreference> loadPreferences(Long sessionId, Long userId) {
        String userCacheKey = RedisKey.PREFERENCE_CACHE_USER + userId;
        String sessionCacheKey = RedisKey.PREFERENCE_CACHE_SESSION + userId + ":" + sessionId;

        List<MemoryPreference> cachedUser = (List<MemoryPreference>) redisTemplate.opsForValue().get(userCacheKey);
        List<MemoryPreference> cachedSession = (List<MemoryPreference>) redisTemplate.opsForValue().get(sessionCacheKey);

        if (cachedUser != null && cachedSession != null) {
            List<MemoryPreference> result = new ArrayList<>(cachedUser);
            result.addAll(cachedSession);
            return result;
        }

        List<MemoryPreference> allPreferences = memoryPreferenceService.getPreference(sessionId, userId);

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

        redisTemplate.opsForValue().set(userCacheKey, userPrefs, 5, TimeUnit.HOURS);
        redisTemplate.opsForValue().set(sessionCacheKey, sessionPrefs, 5, TimeUnit.HOURS);

        return allPreferences;
    }

    /**
     * 异步保存召回 trace 到数据库。
     */
    private void saveTrace(Long sessionId, Long userId, Integer roundNo, String userMessage, RecallContext ctx) {
        try {
            MemoryRecallTrace trace = new MemoryRecallTrace();
            trace.setSessionId(sessionId);
            trace.setUserId(userId);
            trace.setRoundNo(roundNo);
            trace.setQueryText(userMessage != null && userMessage.length() > 500
                    ? userMessage.substring(0, 500) : userMessage);
            trace.setFactCount(ctx.getRelevantFacts().size());
            trace.setFactIds(JSONUtil.toJsonStr(ctx.getFactIds()));
            trace.setFactScores(JSONUtil.toJsonStr(ctx.getFactScores()));
            trace.setFactContents(JSONUtil.toJsonStr(ctx.getRecentFactContents()));
            trace.setPreferenceCount(ctx.getPreferences().size());
            trace.setUnresolvedCount(ctx.getUnresolvedItems().size());
            trace.setHasSummary(ctx.getPreviousSummary() != null && !ctx.getPreviousSummary().isEmpty());
            trace.setTotalLatencyMs((int) ctx.getTotalLatencyMs());
            trace.setFactLatencyMs((int) ctx.getFactLatencyMs());
            trace.setPreferenceLatencyMs((int) ctx.getPreferenceLatencyMs());

            recallTraceMapper.insert(trace);
        } catch (Exception e) {
            log.warn("保存召回trace失败（不影响主流程）: {}", e.getMessage());
        }
    }

    /**
     * 更新被召回事实的使用统计（last_used_at + usage_count++）
     */
    private void updateFactUsage(List<String> factIds) {
        try {
            List<String> validIds = factIds.stream()
                    .filter(id -> id != null && !id.isEmpty())
                    .toList();
            if (validIds.isEmpty()) return;

            LambdaUpdateWrapper<MemoryFact> wrapper = new LambdaUpdateWrapper<>();
            wrapper.in(MemoryFact::getFactId, validIds)
                    .set(MemoryFact::getLastUsedAt, LocalDateTime.now())
                    .setSql("usage_count = usage_count + 1");
            memoryFactService.update(wrapper);
            log.debug("更新事实使用统计, 数量:{}", validIds.size());
        } catch (Exception e) {
            log.warn("更新事实使用统计失败（不影响主流程）: {}", e.getMessage());
        }
    }
}
