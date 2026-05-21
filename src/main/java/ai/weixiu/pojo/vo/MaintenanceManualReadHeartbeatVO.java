package ai.weixiu.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MaintenanceManualReadHeartbeatVO {
    private Long currentDurationSeconds;
    private Boolean counted;
    private Boolean rankIncreased;
}
