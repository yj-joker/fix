package ai.weixiu.pojo.dto;

import lombok.Data;

@Data
public class SolutionDTO {
    private String code;
    private String title;
    private String description;
    private String toolsRequired;
    private Integer estimatedTime;
    private String difficulty;
    private Boolean verified;
}
