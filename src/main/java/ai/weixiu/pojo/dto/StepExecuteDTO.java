package ai.weixiu.pojo.dto;

import lombok.Data;

import java.util.List;

/** 执行某一步骤的请求体 */
@Data
public class StepExecuteDTO {

    /** 工人上传的照片URL列表 */
    private List<String> images;

    /** 工人填写的备注 */
    private String note;
}
