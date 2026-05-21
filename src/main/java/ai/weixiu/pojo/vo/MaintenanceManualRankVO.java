package ai.weixiu.pojo.vo;

import lombok.Data;

@Data
public class MaintenanceManualRankVO {
    private Integer rank;
    private Long manualId;
    private String manualName;
    private String manualImage;
    private String manualDesc;
    private Long score;
}
