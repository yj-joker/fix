package ai.weixiu.pojo.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DeviceDTO {
    private String name;
    private String code;
    private String model;
    private String location;
    private LocalDateTime purchaseDate;
    private String manufacturer;
}
