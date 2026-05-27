package ai.weixiu.pojo.query;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class MaintenanceTaskQuery extends PageQuery {
    /** 按状态过滤 */
    private String status;
    /** 按设备名称模糊搜索 */
    private String deviceName;
}
