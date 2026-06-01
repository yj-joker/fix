package ai.weixiu.scheduler;

import ai.weixiu.entity.MemoryFact;
import ai.weixiu.service.MemoryFactService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 记忆向量补偿任务
 *
 * <p>解决 MySQL↔Redis 双写不一致问题：
 * 整合流程中 MySQL 将事实标记为 superseded 后，调用 Python 删除 Redis 向量可能失败。
 * 本任务定时扫描 MySQL 中已废弃但可能仍残留在 Redis 的事实，批量请求 Python 端清理。</p>
 *
 * <p>设计原则：
 * <ul>
 *   <li>幂等安全：Python delete_facts 对已删除的 doc_id 直接跳过，不会报错</li>
 *   <li>批量处理：每次最多处理 200 条，避免一次性请求量过大</li>
 *   <li>只补偿 1 小时前的数据：给主流程充分时间完成正常删除</li>
 *   <li>失败不阻塞：catch 所有异常，不影响下次调度</li>
 * </ul></p>
 */
@Component
@AllArgsConstructor
@Slf4j
public class MemoryVectorCompensationTask {

    private final MemoryFactService memoryFactService;
    private final WebClient webClient;

    /**
     * 每 6 小时执行一次：清理 Redis 中残留的已废弃事实向量
     *
     * <p>扫描条件：status=superseded 且 supersededAt 在 1 小时前到 7 天前之间。
     * 下限 1 小时：给主流程的同步删除留出时间，避免重复劳动。
     * 上限 7 天：太久远的数据如果还没清掉，说明 factId 可能有问题，不再反复尝试。</p>
     *
     * <p>幂等安全：即使多机部署同时触发，delete_facts 对已删 doc_id 幂等返回 0，
     * 不会重复删除或报错，无需分布式锁。</p>
     */
    @Scheduled(fixedDelay = 21_600_000, initialDelay = 120_000)
    public void compensateStaleVectors() {
        try {
            LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
            LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);

            LambdaQueryWrapper<MemoryFact> query = new LambdaQueryWrapper<>();
            query.eq(MemoryFact::getStatus, "superseded")
                    .isNotNull(MemoryFact::getFactId)
                    .between(MemoryFact::getSupersededAt, sevenDaysAgo, oneHourAgo)
                    .select(MemoryFact::getFactId)
                    .last("LIMIT 200");

            List<MemoryFact> staleFacts = memoryFactService.list(query);
            if (staleFacts.isEmpty()) {
                log.debug("[补偿] 无需清理的过期事实向量");
                return;
            }

            List<String> factIds = staleFacts.stream()
                    .map(MemoryFact::getFactId)
                    .filter(id -> id != null && !id.isEmpty())
                    .distinct()
                    .collect(Collectors.toList());

            if (factIds.isEmpty()) {
                return;
            }

            log.info("[补偿] 开始清理 Redis 残留向量, 数量:{}", factIds.size());

            Map<String, Object> deleteRequest = new HashMap<>();
            deleteRequest.put("fact_ids", factIds);

            String response = webClient.post()
                    .uri("/ai/memory/delete_facts")
                    .bodyValue(deleteRequest)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.info("[补偿] Redis 向量清理完成, 请求数:{}, 响应:{}", factIds.size(), response);

        } catch (Exception e) {
            log.warn("[补偿] 向量补偿任务执行失败（下次重试）: {}", e.getMessage());
        }
    }
}
