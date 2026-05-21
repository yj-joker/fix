package ai.weixiu.service.impl;

import ai.weixiu.common.RedisKey;
import ai.weixiu.entity.MaintenanceManual;
import ai.weixiu.enumerate.MaintenanceManualRankType;
import ai.weixiu.exceprion.NotFoundException;
import ai.weixiu.pojo.vo.MaintenanceManualRankVO;
import ai.weixiu.service.MaintenanceManualRankService;
import ai.weixiu.service.MaintenanceManualService;
import lombok.AllArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@AllArgsConstructor
/**
 * 基于 Redis ZSet 的维修手册有效阅读排行榜服务。
 *
 * <p>排行榜只保存手册 id 和分数。展示字段在查询榜单时再从手册服务读取，
 * 避免手册名称、封面修改或手册删除后，历史榜单里残留过期展示数据。</p>
 */
public class MaintenanceManualRankServiceImpl implements MaintenanceManualRankService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 100;

    private final StringRedisTemplate stringRedisTemplate;
    private final MaintenanceManualService maintenanceManualService;

    @Override
    public void increaseRank(Long manualId) {
        String member = manualId.toString();

        // 一次有效阅读同时更新当前日榜、周榜、月榜和总榜。
        // 周期榜设置过期时间，总榜长期累计。
        increment(getRankKey(MaintenanceManualRankType.DAY), member, 30, TimeUnit.DAYS);
        increment(getRankKey(MaintenanceManualRankType.WEEK), member, 84, TimeUnit.DAYS);
        increment(getRankKey(MaintenanceManualRankType.MONTH), member, 730, TimeUnit.DAYS);
        stringRedisTemplate.opsForZSet().incrementScore(RedisKey.MANUAL_RANK_TOTAL, member, 1);
    }

    @Override
    public List<MaintenanceManualRankVO> getRankList(MaintenanceManualRankType rankType, Integer limit) {
        int rankLimit = normalizeLimit(limit);
        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                .reverseRangeWithScores(getRankKey(rankType), 0, rankLimit - 1L);
        List<MaintenanceManualRankVO> rankList = new ArrayList<>();
        if (tuples == null || tuples.isEmpty()) {
            return rankList;
        }

        int rank = 1;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            Long manualId = parseManualId(tuple.getValue());
            if (manualId == null) {
                continue;
            }
            try {
                // getManualById 会复用详情缓存保护逻辑，
                // 同时过滤掉排行榜生成后已经删除的手册。
                MaintenanceManual manual = maintenanceManualService.getManualById(manualId);
                rankList.add(toRankVO(rank, manual, tuple.getScore()));
                rank++;
            } catch (NotFoundException ignored) {
                // Historic rank keys can outlive deleted manuals.
            }
        }
        return rankList;
    }

    private void increment(String key, String member, long timeout, TimeUnit timeUnit) {
        stringRedisTemplate.opsForZSet().incrementScore(key, member, 1);
        stringRedisTemplate.expire(key, timeout, timeUnit);
    }

    private String getRankKey(MaintenanceManualRankType rankType) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);

        // 榜单周期按业务时区计算，不依赖服务器机器时区，
        // 保证日榜、周榜、月榜 key 的边界稳定。
        return switch (rankType) {
            case DAY -> RedisKey.MANUAL_RANK_DAY + today.format(DateTimeFormatter.BASIC_ISO_DATE);
            case WEEK -> RedisKey.MANUAL_RANK_WEEK
                    + today.get(IsoFields.WEEK_BASED_YEAR)
                    + String.format("%02d", today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            case MONTH -> RedisKey.MANUAL_RANK_MONTH + today.format(DateTimeFormatter.ofPattern("yyyyMM"));
            case TOTAL -> RedisKey.MANUAL_RANK_TOTAL;
        };
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private Long parseManualId(String value) {
        try {
            return value == null ? null : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private MaintenanceManualRankVO toRankVO(int rank, MaintenanceManual manual, Double score) {
        MaintenanceManualRankVO rankVO = new MaintenanceManualRankVO();
        rankVO.setRank(rank);
        rankVO.setManualId(manual.getId());
        rankVO.setManualName(manual.getManualName());
        rankVO.setManualImage(manual.getManualImage());
        rankVO.setManualDesc(manual.getManualDesc());
        rankVO.setScore(score == null ? 0L : score.longValue());
        return rankVO;
    }
}
