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
import java.util.List;
import java.util.Map;

/**
 * 图片向量化工具
 *
 * 调用 Python 端的 /ai/embedding/image 接口，
 * 将多张图片 URL 转为向量，取平均值返回。
 *
 * 向量由 multimodal-embedding-v1 模型生成，
 * 和文字跨模态向量在同一空间，支持文字搜图片。
 */
@Component
@Slf4j
public class ImageEmbeddingUtils {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    public ImageEmbeddingUtils(
            @Value("${ai.python-service-url:http://localhost:5000}") String pythonServiceUrl,
            ObjectMapper objectMapper
    ) {
        this.webClient = WebClient.create(pythonServiceUrl);
        this.objectMapper = objectMapper;
    }

    /**
     * 将多张图片 URL 转为一个平均向量
     *
     * 流程：发送图片 URL 列表到 Python -> 收到每张图的向量 -> 取平均值
     *
     * @param imageUrls 图片 URL 列表（MinIO 地址）
     * @return 平均向量，如果图片列表为空或调用失败返回 null
     */
    public List<Double> getImageEmbedding(List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return null;
        }

        try {
            String response = webClient.post()
                    .uri("/ai/embedding/image")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(objectMapper.writeValueAsString(
                            Map.of("image_urls", imageUrls)
                    ))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(TIMEOUT);

            JsonNode root = objectMapper.readTree(response);
            JsonNode vectorsNode = root.get("vectors");

            if (vectorsNode == null || !vectorsNode.isArray() || vectorsNode.isEmpty()) {
                log.warn("图片向量化返回为空");
                return null;
            }

            // 计算所有向量的平均值
            int dimension = vectorsNode.get(0).size();
            int vectorCount = vectorsNode.size();
            List<Double> avgVector = new ArrayList<>(dimension);

            for (int d = 0; d < dimension; d++) {
                double sum = 0;
                for (int v = 0; v < vectorCount; v++) {
                    sum += vectorsNode.get(v).get(d).asDouble();
                }
                avgVector.add(sum / vectorCount);
            }

            return avgVector;

        } catch (Exception e) {
            log.error("图片向量化失败: {}", e.getMessage());
            return null;
        }
    }
}
