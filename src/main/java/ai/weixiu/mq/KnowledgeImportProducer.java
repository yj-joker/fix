package ai.weixiu.mq;

import ai.weixiu.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 知识导入 MQ 生产者
 *
 * <p>将耗时的 PDF 解析 + 向量化入库任务发送到 MQ，
 * 由 Python 端异步消费处理。Java 端立即返回任务ID给前端。</p>
 *
 * <p>处理完成后 Python 通过 knowledge.result 队列回传结果，
 * 由 {@link KnowledgeResultListener} 更新数据库状态。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeImportProducer {

    private final RabbitTemplate rabbitTemplate;

    /**
     * 发送知识导入任务
     *
     * @param taskId   任务唯一标识（Java 端生成，用于前端查询进度）
     * @param fileUrl  文档路径或 URL
     * @param fileType 文件类型（pdf/docx/txt）
     * @param category 全局分类标签（可选）
     * @param userId   操作用户ID
     */
    public void sendImportTask(String taskId, String fileUrl, String fileType, String category, Long userId) {
        Map<String, Object> message = new HashMap<>();
        message.put("taskId", taskId);
        message.put("fileUrl", fileUrl);
        message.put("fileType", fileType);
        message.put("category", category);
        message.put("userId", userId);
        message.put("timestamp", System.currentTimeMillis());

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.KNOWLEDGE_EXCHANGE,
                RabbitMQConfig.KNOWLEDGE_IMPORT_KEY,
                message
        );

        log.info("[MQ生产] 知识导入任务已发送, taskId={}, fileUrl={}, fileType={}", taskId, fileUrl, fileType);
    }
}
