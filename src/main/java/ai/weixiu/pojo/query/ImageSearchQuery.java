package ai.weixiu.pojo.query;

import lombok.Data;
import java.util.List;

@Data
public class ImageSearchQuery {
    /** 图片 URL 列表（MinIO 地址） */
    private List<String> imageUrls;
    /** 返回数量，默认 10 */
    private int limit = 10;
    /** 最小相似度，默认 0.5 */
    private double minScore = 0.5;
}
