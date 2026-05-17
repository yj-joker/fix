package ai.weixiu.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.time.LocalDateTime;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 用户偏好记忆
 * </p>
 *
 * @author author
 * @since 2026-05-12
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("memory_preference")
public class MemoryPreference implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 会话ID
     */
    @TableField("session_id")
    private String sessionId;

    /*
    * 用户ID
    * */
    @TableField("user_id")
    private Long userId;

    /**
     * 偏好描述
     */
    @TableField("content")
    private String content;

    /**
     * 交互风格|格式要求|工作习惯|关注领域|其他
     */
    @TableField("category")
    private String category;

    /**
     * 第几次压缩产生的
     */
    @TableField("consolidation_seq")
    private Integer consolidationSeq;

    /**
     * 偏好类型 0:用户级(跨会话通用) 1:会话级(仅本次会话)
     */
    @TableField("preference_category")
    private Integer preferenceCategory;

    /**
     * 偏好来源类型 —— 区分偏好的可靠程度
     *
     * "explicit": 用户直接说出来的指令或态度（如"不要写注释"），可信度高
     * "inferred": 从用户行为推断的（如反复追问细节→可能偏好详细回复），需多次确认
     *
     * Java端根据此字段决定存储策略：
     * - explicit → 直接存为有效偏好，同类旧偏好被覆盖
     * - inferred → 存为候选偏好，未来可加置信度机制逐步升级
     */
    @TableField("source_type")
    private String sourceType;

    @TableField("created_at")
    private LocalDateTime createdAt;


}
