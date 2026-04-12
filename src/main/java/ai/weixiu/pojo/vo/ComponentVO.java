package ai.weixiu.pojo.vo;

import lombok.Data;

@Data
public class ComponentVO {
    private String id;
    private String name;
    private String partNumber;
    private String specification;
    private String supplier;
    private String lifecycle;
    private Double unitPrice;
}
