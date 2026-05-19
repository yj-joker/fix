package ai.weixiu.pojo.query;

import lombok.Data;
import java.util.List;

@Data
public class MultimodalSearchQuery {
    /** 文字描述（可选） */
    private String text;
    /** 图片 URL 列表（MinIO 地址，可选） */
    private List<String> imageUrls;
    /** 每页数量，默认 10 */
    private int size = 10;
    /** 页码，默认 0 */
    private int page = 0;
    /** 最小相似度，默认 0.5 */
    private double minScore = 0.5;
}
