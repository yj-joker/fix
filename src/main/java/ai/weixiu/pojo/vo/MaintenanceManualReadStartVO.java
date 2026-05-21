package ai.weixiu.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MaintenanceManualReadStartVO {
    private String readSessionId;
    private Integer heartbeatIntervalSeconds;
    private Integer validReadThresholdSeconds;
}
