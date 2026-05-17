package ai.weixiu.pojo.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BaiDuAsrDTO {

    @JsonProperty("err_no")
    private Integer errNo;

    @JsonProperty("err_msg")
    private String errMsg;

    private String sn;

    private List<String> result;
}
