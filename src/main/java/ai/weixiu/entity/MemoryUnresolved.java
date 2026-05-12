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
 * 未完成事项记忆
 * </p>
 *
 * @author author
 * @since 2026-05-12
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("memory_unresolved")
public class MemoryUnresolved implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 会话ID
     */
    @TableField("session_id")
    private String sessionId;

    /**
     * 待解决描述
     */
    @TableField("content")
    private String content;

    /**
     * 未答复问题|进行中任务|用户待办
     */
    @TableField("type")
    private String type;

    /**
     * 第几次压缩产生的
     */
    @TableField("consolidation_seq")
    private Integer consolidationSeq;
    /*
    * 是否被用户放弃
    * */
    @TableField("status")
    private String status;

    @TableField("created_at")
    private LocalDateTime createdAt;


}
