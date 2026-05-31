package ai.weixiu.pojo.dto;

import lombok.Data;

import java.util.List;

/**
 * 维修手册新增和更新接口接收的基础字段。
 */
@Data
public class MaintenanceManualDTO {
    /** 更新时必填；新增时由服务端生成雪花 id，不信任前端 id。 */
    private Long id;

    /** 手册标题。 */
    private String manualName;

    /** 手册封面地址或封面资源标识。 */
    private String manualImage;

    /** 手册简介，列表和排行榜可用于辅助展示。 */
    private String manualDesc;

    /**
     * 适用设备 ID 列表（Neo4j Device 节点 UUID）。
     * <p>管理员上传/编辑手册时选择该手册适用于哪些设备，
     * 用于个性化推荐：工人 → 检修任务 → 设备 → 手册。</p>
     */
    private List<String> deviceIds;
}
