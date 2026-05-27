package ai.weixiu.mq;

import ai.weixiu.config.RabbitMQConfig;
import ai.weixiu.service.impl.MaintenanceTaskServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 监听 Python 端返回的步骤 AI 验证结果
 *
 * 消息格式：{taskId, stepId, aiPass, confidence, reason}
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class StepVerifyResultListener {

    private final MaintenanceTaskServiceImpl taskService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = RabbitMQConfig.TASK_STEP_VERIFY_RESULT_QUEUE)
    public void onMessage(Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            Map<String, Object> body = objectMapper.readValue(message.getBody(), new TypeReference<>() {});

            Long stepId = parseLong(body.get("stepId"));
            Boolean aiPass = (Boolean) body.get("aiPass");
            Double confidence = body.get("confidence") != null
                    ? ((Number) body.get("confidence")).doubleValue() : null;
            String reason = (String) body.get("reason");

            taskService.onStepVerifyResult(stepId, aiPass, confidence, reason);

            channel.basicAck(deliveryTag, false);
            log.info("[MQ] 步骤AI验证结果处理完成 stepId={} aiPass={} confidence={}", stepId, aiPass, confidence);

        } catch (Exception e) {
            log.error("[MQ] 步骤AI验证结果处理异常", e);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    private Long parseLong(Object obj) {
        if (obj instanceof Number) {
            return ((Number) obj).longValue();
        }
        return Long.parseLong(String.valueOf(obj));
    }
}
