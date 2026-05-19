package ai.weixiu.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多模态融合向量化工具
 *
 * 调用 Python 端的 /ai/embedding/multimodal 接口，
 * 将实体的文字描述 + 图片 URL 融合为单个向量。
 *
 * 向量由 multimodal-embedding-v1 模型生成（1024维），
 * 文字和图片在同一语义空间，支持跨模态检索。
 */
@Component
@Slf4j
public class MultimodalEmbeddingUtils {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    public MultimodalEmbeddingUtils(
            @Value("${ai.python-service-url:http://localhost:5000}") String pythonServiceUrl,
            ObjectMapper objectMapper
    ) {
        this.webClient = WebClient.create(pythonServiceUrl);
        this.objectMapper = objectMapper;
    }

    /**
     * 将文字描述 + 图片 URL 融合为单个向量
     *
     * @param text      实体的文字描述（如故障名称+描述+类别等拼接文本）
     * @param imageUrls 图片 URL 列表（MinIO 地址），可为 null 或空
     * @return 融合向量，如果输入均为空或调用失败返回 null
     */
    public List<Double> getMultimodalEmbedding(String text, List<String> imageUrls) {
        boolean hasText = text != null && !text.isBlank();
        boolean hasImages = imageUrls != null && !imageUrls.isEmpty();

        if (!hasText && !hasImages) {
            return null;
        }

        try {
            Map<String, Object> body = new HashMap<>();
            if (hasText) {
                body.put("text", text);
            }
            if (hasImages) {
                body.put("image_urls", imageUrls);
            }

            String response = webClient.post()
                    .uri("/ai/embedding/multimodal")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(TIMEOUT);

            JsonNode root = objectMapper.readTree(response);
            JsonNode vectorNode = root.get("vector");

            if (vectorNode == null || !vectorNode.isArray() || vectorNode.isEmpty()) {
                log.warn("多模态融合向量化返回为空");
                return null;
            }

            List<Double> vector = new ArrayList<>(vectorNode.size());
            for (JsonNode v : vectorNode) {
                vector.add(v.asDouble());
            }
            return vector;

        } catch (Exception e) {
            log.error("多模态融合向量化失败: {}", e.getMessage());
            return null;
        }
    }
}
