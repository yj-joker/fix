package ai.weixiu.pojo.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MaintenanceManualVO {
    private Long id;
    private String manualName;
    private String manualImage;
    private String manualDesc;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private String fileUrl;
    private Integer status;
    private Long createdById;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
