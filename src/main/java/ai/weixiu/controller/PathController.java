package ai.weixiu.controller;

import ai.weixiu.pojo.Result;
import ai.weixiu.pojo.vo.DiagnosisPathVO;
import ai.weixiu.service.GraphQueryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/weixiu/path")
@AllArgsConstructor
@Tag(name="路径")
public class PathController {
    private final GraphQueryService graphQueryService;
    /*
    * 根据设备信息和故障描述获得诊断路径,最多获取10个
    * */
    @GetMapping("/getPath")
    @Tag(name="获取诊断路径,最多获取10个")
    public Result<List<DiagnosisPathVO>> getPath(String keyword, String faultName) {
        List<DiagnosisPathVO> path = graphQueryService.findDiagnosisPath(keyword, faultName, 10L);
        return Result.success(path);
    }
}
