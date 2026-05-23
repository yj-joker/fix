package ai.weixiu.service.impl;

import ai.weixiu.common.RedisKey;
import ai.weixiu.entity.AiMessage;
import ai.weixiu.entity.MaintenanceManual;
import ai.weixiu.entity.MemoryPreference;
import ai.weixiu.pojo.vo.ManualRecommendVO;
import ai.weixiu.service.ManualRecommendService;
import ai.weixiu.service.MemoryPreferenceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import ai.weixiu.mapper.AiMessageMapper;
import ai.weixiu.mapper.MaintenanceManualMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 维修手册个性化推荐服务实现
 *
 * <h3>三层推荐算法</h3>
 * <ol>
 *   <li><b>画像匹配（权重 3.0）</b>——从用户偏好记忆中提取关注领域，匹配手册名称和描述</li>
 *   <li><b>语境关联（权重 2.0）</b>——从用户近期对话中提取设备/故障关键词，匹配手册</li>
 *   <li><b>时效加分（权重 1.0）</b>——最近 7 天内更新的手册额外加分</li>
 * </ol>
 *
 * <h3>缓存策略</h3>
 * <ul>
 *   <li>缓存 key：{@code Recommend:Manual:{userId}}，TTL 2 小时</li>
 *   <li>刷新时机：每次对话完成后异步刷新、偏好变更时清除</li>
 *   <li>降级策略：新用户/缓存未命中时实时计算</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManualRecommendServiceImpl implements ManualRecommendService {

    private final MemoryPreferenceService memoryPreferenceService;
    private final AiMessageMapper aiMessageMapper;
    private final MaintenanceManualMapper maintenanceManualMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    /** 推荐缓存 TTL：2小时 */
    private static final long CACHE_TTL_HOURS = 2;

    /** 刷新冷却时间：30分钟内不重复刷新 */
    private static final long REFRESH_COOLDOWN_MINUTES = 30;

    /** 刷新冷却 key 前缀 */
    private static final String REFRESH_COOLDOWN_KEY = "Recommend:Manual:Cooldown:";

    /** SQL预筛选时最多使用的关键词数量（避免 OR 条件过多） */
    private static final int MAX_SQL_KEYWORDS = 15;

    /** SQL预筛选最多返回的候选手册数量 */
    private static final int MAX_CANDIDATE_COUNT = 50;

    /** 参与近期语境分析的消息条数 */
    private static final int RECENT_MESSAGE_COUNT = 20;

    /** 中文常见停用词（排除无意义匹配） */
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人",
            "都", "一", "一个", "上", "也", "很", "到", "说", "要", "去",
            "你", "会", "着", "没有", "看", "好", "自己", "这", "他", "她",
            "什么", "怎么", "可以", "吗", "吧", "呢", "啊", "哦", "嗯",
            "请", "帮", "问题", "为什么", "如何", "能", "想", "知道",
            "谢谢", "好的", "请问", "能不能", "告诉", "一下", "还有"
    );

    @Override
    @SuppressWarnings("unchecked")
    public List<ManualRecommendVO> getRecommendations(Long userId, int limit) {
        // 1. 优先查缓存
        String cacheKey = RedisKey.MANUAL_RECOMMEND + userId;
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached instanceof List) {
            List<ManualRecommendVO> cachedList = (List<ManualRecommendVO>) cached;
            log.debug("推荐缓存命中, userId={}, 数量={}", userId, cachedList.size());
            return cachedList.stream().limit(limit).collect(Collectors.toList());
        }

        // 2. 缓存未命中，实时计算
        List<ManualRecommendVO> recommendations = computeRecommendations(userId, limit);

        // 3. 写入缓存
        redisTemplate.opsForValue().set(cacheKey, recommendations, CACHE_TTL_HOURS, TimeUnit.HOURS);

        return recommendations;
    }

    @Override
    @Async
    public void refreshAsync(Long userId) {
        try {
            String cacheKey = RedisKey.MANUAL_RECOMMEND + userId;

            // 条件1：缓存不存在说明用户从没访问过推荐，不需要提前算
            if (Boolean.FALSE.equals(redisTemplate.hasKey(cacheKey))) {
                log.debug("推荐缓存不存在，跳过刷新, userId={}", userId);
                return;
            }

            // 条件2：冷却期内不重复刷新（防止用户连续对话时反复重算）
            String cooldownKey = REFRESH_COOLDOWN_KEY + userId;
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(cooldownKey, "1", REFRESH_COOLDOWN_MINUTES, TimeUnit.MINUTES);
            if (Boolean.FALSE.equals(acquired)) {
                log.debug("推荐刷新冷却中，跳过, userId={}", userId);
                return;
            }

            List<ManualRecommendVO> recommendations = computeRecommendations(userId, 10);
            redisTemplate.opsForValue().set(cacheKey, recommendations, CACHE_TTL_HOURS, TimeUnit.HOURS);
            log.info("推荐缓存异步刷新完成, userId={}, 推荐数={}", userId, recommendations.size());
        } catch (Exception e) {
            log.warn("推荐缓存刷新失败, userId={}, error={}", userId, e.getMessage());
        }
    }

    @Override
    public void invalidateCache(Long userId) {
        String cacheKey = RedisKey.MANUAL_RECOMMEND + userId;
        String cooldownKey = REFRESH_COOLDOWN_KEY + userId;
        redisTemplate.delete(List.of(cacheKey, cooldownKey));
        log.info("推荐缓存和冷却锁已清除, userId={}", userId);
    }

    // ==================== 核心推荐计算 ====================

    /**
     * 计算推荐列表
     *
     * <p>两阶段策略：</p>
     * <ol>
     *   <li><b>SQL 预筛选</b>：用高权重关键词做 LIKE 查询，从数据库中捞出候选手册（最多 {@value MAX_CANDIDATE_COUNT} 本）</li>
     *   <li><b>内存精排</b>：对候选手册逐本评分、排序、截取</li>
     * </ol>
     * <p>当关键词为空（新用户）或预筛选结果不足时，自动用最新手册补齐。</p>
     */
    private List<ManualRecommendVO> computeRecommendations(Long userId, int limit) {
        long start = System.currentTimeMillis();

        // 1. 构建用户画像
        UserProfile profile = buildUserProfile(userId);
        if (profile.isEmpty()) {
            log.info("用户画像为空（新用户），返回最新手册, userId={}", userId);
            return fallbackLatestManuals(limit);
        }

        // 2. SQL 预筛选：用高权重关键词缩小候选范围
        List<MaintenanceManual> candidates = preFilterManuals(profile);

        if (candidates.isEmpty()) {
            log.info("SQL预筛选无结果，返回最新手册, userId={}", userId);
            return fallbackLatestManuals(limit);
        }

        // 3. 内存精排：逐本评分
        List<ScoredManual> scoredList = new ArrayList<>();
        for (MaintenanceManual manual : candidates) {
            ScoredManual scored = scoreManual(manual, profile);
            if (scored.totalScore > 0) {
                scoredList.add(scored);
            }
        }

        // 4. 按分数降序排序，取 top N
        scoredList.sort(Comparator.comparingDouble((ScoredManual s) -> s.totalScore).reversed());

        List<ManualRecommendVO> result = scoredList.stream()
                .limit(limit)
                .map(this::toVO)
                .collect(Collectors.toList());

        // 推荐数不够时用最新手册补齐
        if (result.size() < limit) {
            Set<Long> existingIds = result.stream().map(ManualRecommendVO::getId).collect(Collectors.toSet());
            List<MaintenanceManual> latest = maintenanceManualMapper.selectList(
                    Wrappers.<MaintenanceManual>lambdaQuery()
                            .eq(MaintenanceManual::getStatus, 1)
                            .notIn(!existingIds.isEmpty(), MaintenanceManual::getId, existingIds)
                            .orderByDesc(MaintenanceManual::getCreatedAt)
                            .last("LIMIT " + (limit - result.size()))
            );
            for (MaintenanceManual manual : latest) {
                ManualRecommendVO vo = new ManualRecommendVO();
                vo.setId(manual.getId());
                vo.setManualName(manual.getManualName());
                vo.setManualDesc(manual.getManualDesc());
                vo.setManualImage(manual.getManualImage());
                vo.setFileType(manual.getFileType());
                vo.setFileSize(manual.getFileSize());
                vo.setCreatedAt(manual.getCreatedAt());
                vo.setScore(0);
                vo.setReason("最新手册");
                result.add(vo);
            }
        }

        log.info("推荐计算完成, userId={}, 候选手册={}, 推荐数={}, 耗时={}ms",
                userId, candidates.size(), result.size(), System.currentTimeMillis() - start);

        return result;
    }

    /**
     * SQL 预筛选：用关键词做 LIKE 查询，把全表扫描缩小到几十条候选
     *
     * <p>优先使用领域关键词（权重最高），不足时补充近期对话关键词。
     * 最终生成 SQL 类似：WHERE status=1 AND (manual_name LIKE '%轴承%' OR manual_desc LIKE '%轴承%' OR ...)</p>
     */
    private List<MaintenanceManual> preFilterManuals(UserProfile profile) {
        // 收集用于 SQL 筛选的关键词，优先领域 > 近期对话 > 通用
        List<String> sqlKeywords = new ArrayList<>();

        // 领域关键词优先，只取 >= 2字的（太短的 LIKE 匹配范围太广）
        profile.domainKeywords.stream()
                .filter(k -> k.length() >= 2)
                .forEach(sqlKeywords::add);

        // 不够时补近期对话关键词
        if (sqlKeywords.size() < MAX_SQL_KEYWORDS) {
            profile.recentKeywords.stream()
                    .filter(k -> k.length() >= 2)
                    .limit(MAX_SQL_KEYWORDS - sqlKeywords.size())
                    .forEach(sqlKeywords::add);
        }

        if (sqlKeywords.isEmpty()) {
            return Collections.emptyList();
        }

        // 截断避免 SQL 过长
        if (sqlKeywords.size() > MAX_SQL_KEYWORDS) {
            sqlKeywords = sqlKeywords.subList(0, MAX_SQL_KEYWORDS);
        }

        // 构建 OR 条件：(name LIKE '%kw1%' OR desc LIKE '%kw1%') OR (name LIKE '%kw2%' OR desc LIKE '%kw2%') ...
        LambdaQueryWrapper<MaintenanceManual> wrapper = Wrappers.<MaintenanceManual>lambdaQuery()
                .eq(MaintenanceManual::getStatus, 1);

        List<String> finalKeywords = sqlKeywords;
        wrapper.and(w -> {
            for (int i = 0; i < finalKeywords.size(); i++) {
                String kw = finalKeywords.get(i);
                if (i == 0) {
                    w.and(inner -> inner.like(MaintenanceManual::getManualName, kw)
                            .or().like(MaintenanceManual::getManualDesc, kw));
                } else {
                    w.or(inner -> inner.like(MaintenanceManual::getManualName, kw)
                            .or().like(MaintenanceManual::getManualDesc, kw));
                }
            }
        });

        wrapper.orderByDesc(MaintenanceManual::getCreatedAt)
                .last("LIMIT " + MAX_CANDIDATE_COUNT);

        return maintenanceManualMapper.selectList(wrapper);
    }

    // ==================== 用户画像构建 ====================

    /**
     * 构建用户画像
     *
     * <p>从两个维度收集关键词：</p>
     * <ol>
     *   <li>偏好记忆（用户级，跨会话有效）：关注领域、工作习惯等</li>
     *   <li>近期对话（最近 N 条 user 消息）：正在关注什么设备/故障</li>
     * </ol>
     */
    private UserProfile buildUserProfile(Long userId) {
        UserProfile profile = new UserProfile();

        // ---- 第一维度：偏好记忆 ----
        List<MemoryPreference> preferences = memoryPreferenceService.getUserLevelPreferences(userId);
        for (MemoryPreference pref : preferences) {
            Set<String> keywords = extractKeywords(pref.getContent());
            // "关注领域"类别的偏好权重更高
            if ("关注领域".equals(pref.getCategory())) {
                profile.domainKeywords.addAll(keywords);
            } else {
                profile.generalKeywords.addAll(keywords);
            }
        }

        // ---- 第二维度：近期对话消息 ----
        LambdaQueryWrapper<AiMessage> msgQuery = Wrappers.<AiMessage>lambdaQuery()
                .eq(AiMessage::getUserId, userId)
                .eq(AiMessage::getRole, "user")
                .orderByDesc(AiMessage::getCreatedAt)
                .last("LIMIT " + RECENT_MESSAGE_COUNT);
        List<AiMessage> recentMessages = aiMessageMapper.selectList(msgQuery);

        for (AiMessage msg : recentMessages) {
            profile.recentKeywords.addAll(extractKeywords(msg.getContent()));
        }

        log.debug("用户画像构建完成, userId={}, 领域关键词={}, 通用关键词={}, 近期关键词={}",
                userId, profile.domainKeywords.size(), profile.generalKeywords.size(), profile.recentKeywords.size());

        return profile;
    }

    // ==================== 手册评分 ====================

    /**
     * 对单本手册评分
     *
     * <p>评分规则：</p>
     * <ul>
     *   <li>领域关键词命中手册名称：+3.0 / 命中描述：+1.5</li>
     *   <li>近期对话关键词命中手册名称：+2.0 / 命中描述：+1.0</li>
     *   <li>通用偏好关键词命中名称：+1.0 / 命中描述：+0.5</li>
     *   <li>7天内新增的手册：+1.0 时效加分</li>
     * </ul>
     */
    private ScoredManual scoreManual(MaintenanceManual manual, UserProfile profile) {
        ScoredManual scored = new ScoredManual();
        scored.manual = manual;

        String name = manual.getManualName() != null ? manual.getManualName() : "";
        String desc = manual.getManualDesc() != null ? manual.getManualDesc() : "";
        String searchText = name + " " + desc;

        List<String> matchedReasons = new ArrayList<>();

        // 第一层：领域偏好匹配（权重最高）
        for (String keyword : profile.domainKeywords) {
            if (name.contains(keyword)) {
                scored.totalScore += 3.0;
                matchedReasons.add("关注领域「" + keyword + "」");
            } else if (desc.contains(keyword)) {
                scored.totalScore += 1.5;
                matchedReasons.add("关注领域「" + keyword + "」");
            }
        }

        // 第二层：近期对话语境匹配
        int recentHits = 0;
        for (String keyword : profile.recentKeywords) {
            if (name.contains(keyword)) {
                scored.totalScore += 2.0;
                recentHits++;
            } else if (desc.contains(keyword)) {
                scored.totalScore += 1.0;
                recentHits++;
            }
        }
        if (recentHits > 0) {
            matchedReasons.add("与近期对话相关（命中" + recentHits + "个关键词）");
        }

        // 第三层：通用偏好匹配
        for (String keyword : profile.generalKeywords) {
            if (searchText.contains(keyword)) {
                scored.totalScore += 0.5;
            }
        }

        // 时效加分：7天内新手册
        if (manual.getCreatedAt() != null) {
            long daysSinceCreated = java.time.Duration.between(
                    manual.getCreatedAt(), java.time.LocalDateTime.now()
            ).toDays();
            if (daysSinceCreated <= 7) {
                scored.totalScore += 1.0;
                matchedReasons.add("近期新增");
            }
        }

        // 拼装推荐理由
        if (matchedReasons.isEmpty()) {
            scored.reason = "";
        } else if (matchedReasons.size() <= 2) {
            scored.reason = String.join("、", matchedReasons);
        } else {
            scored.reason = matchedReasons.get(0) + "、" + matchedReasons.get(1)
                    + "等" + matchedReasons.size() + "个匹配";
        }

        return scored;
    }

    // ==================== 关键词提取 ====================

    /**
     * 从文本中提取有意义的关键词
     *
     * <p>策略：按标点符号和空格分词 → 过滤停用词和过短词 → 去重</p>
     * <p>例：「用户关注电动机轴承故障维修」→ {电动机, 轴承, 故障, 维修}</p>
     */
    private Set<String> extractKeywords(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptySet();
        }

        Set<String> keywords = new LinkedHashSet<>();

        // 按常见分隔符拆分
        String[] segments = text.split("[，,。.！!？?；;：:、\\s\\n\\r]+");

        for (String segment : segments) {
            String trimmed = segment.trim();
            // 过滤太短的和停用词
            if (trimmed.length() < 2 || STOP_WORDS.contains(trimmed)) {
                continue;
            }

            // 对较长的句子做滑动窗口提取 2-4 字的子词
            if (trimmed.length() > 4) {
                // 先把完整短语加入
                if (trimmed.length() <= 8) {
                    keywords.add(trimmed);
                }
                // 滑动窗口提取子关键词（2字、3字、4字）
                for (int len = 2; len <= Math.min(4, trimmed.length()); len++) {
                    for (int i = 0; i <= trimmed.length() - len; i++) {
                        String sub = trimmed.substring(i, i + len);
                        if (!STOP_WORDS.contains(sub)) {
                            keywords.add(sub);
                        }
                    }
                }
            } else {
                keywords.add(trimmed);
            }
        }

        return keywords;
    }

    // ==================== 降级策略 ====================

    /** 新用户无画像时，返回最新的手册 */
    private List<ManualRecommendVO> fallbackLatestManuals(int limit) {
        List<MaintenanceManual> latest = maintenanceManualMapper.selectList(
                Wrappers.<MaintenanceManual>lambdaQuery()
                        .eq(MaintenanceManual::getStatus, 1)
                        .orderByDesc(MaintenanceManual::getCreatedAt)
                        .last("LIMIT " + limit)
        );
        return latest.stream().map(m -> {
            ManualRecommendVO vo = new ManualRecommendVO();
            vo.setId(m.getId());
            vo.setManualName(m.getManualName());
            vo.setManualDesc(m.getManualDesc());
            vo.setManualImage(m.getManualImage());
            vo.setFileType(m.getFileType());
            vo.setFileSize(m.getFileSize());
            vo.setCreatedAt(m.getCreatedAt());
            vo.setScore(0);
            vo.setReason("最新手册");
            return vo;
        }).collect(Collectors.toList());
    }

    private ManualRecommendVO toVO(ScoredManual scored) {
        ManualRecommendVO vo = new ManualRecommendVO();
        MaintenanceManual m = scored.manual;
        vo.setId(m.getId());
        vo.setManualName(m.getManualName());
        vo.setManualDesc(m.getManualDesc());
        vo.setManualImage(m.getManualImage());
        vo.setFileType(m.getFileType());
        vo.setFileSize(m.getFileSize());
        vo.setCreatedAt(m.getCreatedAt());
        vo.setScore(Math.round(scored.totalScore * 100.0) / 100.0);
        vo.setReason(scored.reason);
        return vo;
    }

    // ==================== 内部数据结构 ====================

    /** 用户画像 */
    private static class UserProfile {
        /** 领域偏好关键词（权重最高）：从 category=关注领域 的偏好中提取 */
        Set<String> domainKeywords = new LinkedHashSet<>();
        /** 通用偏好关键词（权重低）：从其他偏好中提取 */
        Set<String> generalKeywords = new LinkedHashSet<>();
        /** 近期对话关键词（权重中）：从最近N条消息中提取 */
        Set<String> recentKeywords = new LinkedHashSet<>();

        boolean isEmpty() {
            return domainKeywords.isEmpty() && generalKeywords.isEmpty() && recentKeywords.isEmpty();
        }
    }

    /** 带评分的手册 */
    private static class ScoredManual {
        MaintenanceManual manual;
        double totalScore;
        String reason;
    }
}
