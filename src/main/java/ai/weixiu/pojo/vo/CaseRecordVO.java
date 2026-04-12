package ai.weixiu.pojo.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CaseRecordVO {
    private String id;
    private String caseNumber;
    private String title;
    private String summary;
    private String diagnosis;
    private String resolution;
    private String result;
    private Integer downtime;
    private Double cost;
    private LocalDateTime recordedAt;
    private String recorder;
    private String reviewedBy;
    private String tags;
}
