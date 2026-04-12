package ai.weixiu.pojo.dto;

import lombok.Data;

@Data
public class CaseRecordDTO {
    private String caseNumber;
    private String title;
    private String summary;
    private String diagnosis;
    private String resolution;
    private String result;
    private Integer downtime;
    private Double cost;
    private String recorder;
    private String reviewedBy;
    private String tags;
}
