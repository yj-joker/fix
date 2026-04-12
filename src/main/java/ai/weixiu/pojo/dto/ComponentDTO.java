package ai.weixiu.pojo.dto;

import lombok.Data;

@Data
public class ComponentDTO {
    private String name;
    private String partNumber;
    private String specification;
    private String supplier;
    private String lifecycle;
    private Double unitPrice;
}
