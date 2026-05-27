package ai.weixiu.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName(value = "maintenance_task", autoResultMap = true)
public class MaintenanceTask implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 任务编号 MT-yyyyMMdd-xxx */
    private String taskNumber;

    /** 设备ID（图谱节点ID） */
    private String deviceId;

    /** 设备名称 */
    private String deviceName;

    /** 故障描述 */
    private String faultDescription;

    /** 紧急等级 0低 1普通 2紧急 */
    private Integer urgencyLevel;

    /** 报修图片URL列表 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> reportImages;

    /** 状态: CREATED / GENERATING / GENERATED / GENERATE_FAILED / EXECUTING / CLOSED */
    private String status;

    /** 步骤总数（冗余） */
    private Integer stepCount;

    /** 报修人ID */
    private Long reporterId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
