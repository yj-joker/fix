package ai.weixiu.controller;

import ai.weixiu.pojo.PageResult;
import ai.weixiu.pojo.Result;
import ai.weixiu.pojo.query.DiagnosisSearchQuery;
import ai.weixiu.pojo.vo.DiagnosisPathVO;
import ai.weixiu.service.GraphQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/weixiu/path")
@AllArgsConstructor
@Tag(name = "路径")
public class PathController {
    private final GraphQueryService graphQueryService;

    @PostMapping("/search")
    @Operation(summary = "统一诊断路径查询（支持文本+图片+设备关键词）")
    public Result<PageResult<DiagnosisPathVO>> searchDiagnosisPaths(@RequestBody DiagnosisSearchQuery query) {
        return Result.success(graphQueryService.searchDiagnosisPaths(query));
    }
}
