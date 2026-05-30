package ai.weixiu.pojo.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class TaskStepRecordVO {
    private Long id;
    private Long taskId;
    private Integer sortOrder;
    private String title;
    private String content;
    private String safetyNote;
    private Boolean requirePhoto;
    private Boolean requireNote;
    private Integer estimatedMinutes;
    private String status;
    private List<String> images;
    private String note;
    private LocalDateTime completedAt;

    // ===== 合规检查点 =====
    private Boolean isCheckpoint;
    private List<String> checkpointItems;
    private Boolean checkpointConfirmed;

    // ===== 步骤来源溯源 =====
    private Object sources;
    private BigDecimal generateConfidence;

    // ===== AI 验收 + 人工审核 =====
    private Boolean aiPass;
    private BigDecimal aiConfidence;
    private String aiReason;
    private String reviewStatus;
    private Long reviewerId;
    private String reviewNote;
    private LocalDateTime reviewedAt;
}
