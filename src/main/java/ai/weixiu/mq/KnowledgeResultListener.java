package ai.weixiu.mq;

import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 知识导入结果监听器
 *
 * <p>消费 Python 端完成知识导入后回传的结果消息，
 * 将任务状态写入 Redis 供前端轮询查询。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeResultListener {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String TASK_STATUS_PREFIX = "knowledge:import:status:";

    @RabbitListener(queues = "knowledge.result.queue")
    public void onResult(Map<String, Object> message, Channel channel,
                         @Header(org.springframework.amqp.support.AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        String taskId = String.valueOf(message.get("taskId"));

        try {
            boolean success = Boolean.TRUE.equals(message.get("success"));
            String statusKey = TASK_STATUS_PREFIX + taskId;

            if (success) {
                Map<String, Object> data = (Map<String, Object>) message.get("data");
                redisTemplate.opsForValue().set(statusKey, Map.of(
                        "status", "completed",
                        "totalChunks", data != null ? data.getOrDefault("total_chunks", 0) : 0,
                        "message", "导入完成"
                ), 1, TimeUnit.HOURS);
                log.info("[MQ消费] 知识导入完成, taskId={}", taskId);
            } else {
                String error = String.valueOf(message.getOrDefault("error", "未知错误"));
                redisTemplate.opsForValue().set(statusKey, Map.of(
                        "status", "failed",
                        "message", error
                ), 1, TimeUnit.HOURS);
                log.error("[MQ消费] 知识导入失败, taskId={}, error={}", taskId, error);
            }

            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("[MQ消费] 处理知识导入结果异常, taskId={}", taskId, e);
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
