package ai.weixiu.pojo.vo;

import lombok.Data;

import java.util.List;

@Data
public class DiagnosisPathVO {
    private String deviceId; // 设备ID
    private String deviceName; // 设备名称
    private String componentId; // 部件ID
    private String componentName; // 部件名称

    private String faultId; // 故障ID
    private String faultName; // 故障名称
    private String faultSeverity; // 故障等级

    private String solutionId; // 解决方案ID
    private String solutionTitle; // 解决方案标题
    private Integer estimatedTime; // 预计解决时间
    private Boolean verified; // 是否经过验证

    private List<String> faultImageUrls; // 故障图片
    private List<String> componentImageUrls; // 部件图片

    private String pathText; // 诊断路径文本
    private Double faultScore; // 故障分数
    private Double componentScore; // 部件分数
}
