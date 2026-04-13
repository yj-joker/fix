package ai.weixiu.pojo.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FaultDTO {
    private String id;
    private String code;
    private String name;
    private String description;
    private String severity;
    private String category;
    private LocalDateTime occurrenceTime;
    private String reportedBy;
}
