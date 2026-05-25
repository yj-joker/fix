package ai.weixiu.mq;

import ai.weixiu.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeImportProducer {

    private final RabbitTemplate rabbitTemplate;

    /**
     * 发送知识导入任务
     *
     * @param documentId      文档唯一标识（= knowledge_document.document_id）
     * @param fileUrl         文档预签名 URL
     * @param fileType        文件类型（pdf/docx/txt）
     * @param category        全局分类标签（可选）
     * @param userId          操作用户ID
     * @param documentVersion 版本标识如 "v1"（可选）
     * @param deviceType      设备类型（可选）
     * @param manualType      手册类型（可选）
     * @param replaceExisting 是否先删除旧版本向量再导入
     */
    public void sendImportTask(String documentId, String fileUrl, String fileType,
                               String category, Long userId,
                               String documentVersion, String deviceType,
                               String manualType, boolean replaceExisting) {
        Map<String, Object> message = new HashMap<>();
        message.put("taskId", documentId);
        message.put("fileUrl", fileUrl);
        message.put("fileType", fileType);
        message.put("category", category);
        message.put("userId", userId);
        message.put("documentId", documentId);
        message.put("documentVersion", documentVersion);
        message.put("deviceType", deviceType);
        message.put("manualType", manualType);
        message.put("replaceExisting", replaceExisting);
        message.put("timestamp", System.currentTimeMillis());

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.KNOWLEDGE_EXCHANGE,
                RabbitMQConfig.KNOWLEDGE_IMPORT_KEY,
                message
        );

        log.info("[MQ生产] 知识导入任务已发送, documentId={}, fileUrl={}, version={}",
                documentId, fileUrl, documentVersion);
    }
}
