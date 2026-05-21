package ai.weixiu.service.impl;

import ai.weixiu.common.RedisKey;
import ai.weixiu.exceprion.NotFoundException;
import ai.weixiu.exceprion.NullException;
import ai.weixiu.pojo.vo.MaintenanceManualReadHeartbeatVO;
import ai.weixiu.pojo.vo.MaintenanceManualReadStartVO;
import ai.weixiu.service.MaintenanceManualRankService;
import ai.weixiu.service.MaintenanceManualReadService;
import ai.weixiu.service.MaintenanceManualService;
import ai.weixiu.utils.BaseContext;
import lombok.AllArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@AllArgsConstructor
/**
 * 维修手册阅读会话与心跳累计服务。
 *
 * <p>单次阅读会话可以很短，但阅读时长累计按“用户 + 手册 + 当天”保存。
 * 这样用户一天内多次短时查看同一本手册，也能逐步累计到 60 秒有效阅读阈值。</p>
 */
public class MaintenanceManualReadServiceImpl implements MaintenanceManualReadService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int HEARTBEAT_INTERVAL_SECONDS = 20;
    private static final int MAX_HEARTBEAT_SECONDS = 30;
    private static final int VALID_READ_THRESHOLD_SECONDS = 60;
    private static final Duration READ_SESSION_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final MaintenanceManualService maintenanceManualService;
    private final MaintenanceManualRankService maintenanceManualRankService;

    @Override
    public MaintenanceManualReadStartVO start(Long manualId) {
        if (manualId == null) {
            throw new NullException("Maintenance manual id cannot be empty");
        }
        Long userId = currentUserId();
        maintenanceManualService.getManualById(manualId);

        // 阅读会话将后续心跳绑定到当前登录用户和当前手册，
        // 前端只需要保存 readSessionId 并在心跳时带回。
        String readSessionId = UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();
        Map<String, String> session = new HashMap<>();
        session.put("userId", userId.toString());
        session.put("manualId", manualId.toString());
        session.put("startAt", Long.toString(now));
        session.put("lastHeartbeatAt", Long.toString(now));
        String sessionKey = RedisKey.MANUAL_READ_SESSION + readSessionId;
        stringRedisTemplate.opsForHash().putAll(sessionKey, session);
        stringRedisTemplate.expire(sessionKey, READ_SESSION_TTL);
        return new MaintenanceManualReadStartVO(readSessionId, HEARTBEAT_INTERVAL_SECONDS, VALID_READ_THRESHOLD_SECONDS);
    }

    @Override
    public MaintenanceManualReadHeartbeatVO heartbeat(String readSessionId) {
        if (!StringUtils.hasText(readSessionId)) {
            throw new NullException("Read session id cannot be empty");
        }
        Long userId = currentUserId();

        // 同一阅读会话的连续或并发心跳不能同时使用同一个 lastHeartbeatAt，
        // 否则会把同一段阅读秒数重复累计。
        RLock lock = redissonClient.getLock(RedisKey.MANUAL_READ_SESSION_LOCK + readSessionId);
        boolean locked = false;
        try {
            locked = lock.tryLock(1, 10, TimeUnit.SECONDS);
            if (!locked) {
                throw new IllegalArgumentException("Read heartbeat is too frequent");
            }
            return heartbeatLocked(readSessionId, userId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Read heartbeat lock interrupted", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private MaintenanceManualReadHeartbeatVO heartbeatLocked(String readSessionId, Long userId) {
        String sessionKey = RedisKey.MANUAL_READ_SESSION + readSessionId;
        Map<Object, Object> session = stringRedisTemplate.opsForHash().entries(sessionKey);
        if (session.isEmpty()) {
            throw new NotFoundException("Read session expired");
        }
        Long sessionUserId = parseLong(session.get("userId"));
        Long manualId = parseLong(session.get("manualId"));
        Long lastHeartbeatAt = parseLong(session.get("lastHeartbeatAt"));
        if (!Objects.equals(sessionUserId, userId) || manualId == null || lastHeartbeatAt == null) {
            throw new IllegalArgumentException("Invalid read session");
        }

        long now = System.currentTimeMillis();
        long elapsedSeconds = Math.max(0, (now - lastHeartbeatAt) / 1000);

        // 阅读秒数以服务端时间计算，并限制单次心跳最多累计的秒数，
        // 防止客户端长时间断开后一次性补报异常长的阅读时长。
        long effectiveSeconds = Math.min(elapsedSeconds, MAX_HEARTBEAT_SECONDS);
        String durationKey = getDurationKey(userId, manualId);
        Long currentDuration = readDuration(durationKey);
        if (effectiveSeconds > 0) {
            currentDuration = stringRedisTemplate.opsForValue().increment(durationKey, effectiveSeconds);
            stringRedisTemplate.expire(durationKey, currentDayTtl());
        }

        stringRedisTemplate.opsForHash().put(sessionKey, "lastHeartbeatAt", Long.toString(now));
        stringRedisTemplate.expire(sessionKey, READ_SESSION_TTL);

        boolean rankIncreased = false;
        String countedKey = getCountedKey(userId, manualId);
        if (currentDuration != null && currentDuration >= VALID_READ_THRESHOLD_SECONDS) {
            // setIfAbsent 用于抢占“当天已计榜”标记。
            // 标记写入成功后，后续会话仍可继续上报阅读时长，
            // 但当天不会再次把同一用户对同一手册的阅读写入排行榜。
            Boolean countClaimed = stringRedisTemplate.opsForValue()
                    .setIfAbsent(countedKey, "1", currentDayTtl());
            if (Boolean.TRUE.equals(countClaimed)) {
                try {
                    maintenanceManualRankService.increaseRank(manualId);
                    rankIncreased = true;
                } catch (RuntimeException e) {
                    stringRedisTemplate.delete(countedKey);
                    throw e;
                }
            }
        }
        boolean counted = Boolean.TRUE.equals(stringRedisTemplate.hasKey(countedKey));
        return new MaintenanceManualReadHeartbeatVO(currentDuration == null ? 0L : currentDuration, counted, rankIncreased);
    }

    private Long currentUserId() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            throw new IllegalArgumentException("User must login before reading manual");
        }
        return userId;
    }

    private Long readDuration(String durationKey) {
        String duration = stringRedisTemplate.opsForValue().get(durationKey);
        return duration == null ? 0L : Long.parseLong(duration);
    }

    private Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String getDurationKey(Long userId, Long manualId) {
        return RedisKey.MANUAL_READ_DURATION + userId + ":" + manualId + ":" + currentDay();
    }

    private String getCountedKey(Long userId, Long manualId) {
        return RedisKey.MANUAL_READ_COUNTED + userId + ":" + manualId + ":" + currentDay();
    }

    private String currentDay() {
        return LocalDate.now(BUSINESS_ZONE).format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private Duration currentDayTtl() {
        // 当天累计时长和计榜标记延续到上海时区次日零点后一小时，
        // 给跨日边界附近的心跳处理留出缓冲。
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        LocalDateTime tomorrow = LocalDateTime.of(LocalDate.now(BUSINESS_ZONE).plusDays(1), LocalTime.MIDNIGHT);
        return Duration.between(now, tomorrow).plusHours(1);
    }
}
