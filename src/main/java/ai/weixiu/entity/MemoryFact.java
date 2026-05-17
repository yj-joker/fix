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
 * 提取的事实记忆
 * </p>
 *
 * @author author
 * @since 2026-05-12
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("memory_fact")
public class MemoryFact implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID —— 支持跨会话检索事实记忆
     * 之前事实只有sessionId，用户开新会话就丢失了所有历史事实。
     * 加上userId后，可以在所有会话中检索该用户的历史事实。
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 会话ID —— 标记事实来源于哪个会话
     */
    @TableField("session_id")
    private String sessionId;

    /**
     * 向量库doc_id —— 对应Redis向量库中的文档ID，用于supersede时引用旧事实
     */
    @TableField("fact_id")
    private String factId;

    /**
     * 事实内容
     */
    @TableField("content")
    private String content;

    /**
     * 检索关键词
     */
    @TableField("keywords")
    private String keywords;

    /**
     * 来源对话序号范围（如"3-5"）
     */
    @TableField("source_seq_range")
    private String sourceSeqRange;

    /**
     * 状态
     */
    @TableField("status")
    private String status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 被覆盖的时间
     */
    @TableField("superseded_at")
    private LocalDateTime supersededAt;


}
