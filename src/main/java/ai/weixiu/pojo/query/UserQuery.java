package ai.weixiu.pojo.query;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserQuery extends PageQuery{
    private String name;
    private String number;
    private Integer gender;
    private String phone;
    private LocalDateTime hireDate;
}
