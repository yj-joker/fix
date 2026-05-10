package ai.weixiu.pojo.vo;

import lombok.Data;

@Data
public class DiagnosisPathVO {
    private String componentId;
    private String componentName;

    private String faultId;
    private String faultName;
    private String faultSeverity;

    private String solutionId;
    private String solutionTitle;
    private Integer estimatedTime;
    private Boolean verified;
}
