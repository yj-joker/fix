package ai.weixiu.controller;

import ai.weixiu.pojo.PageResult;
import ai.weixiu.pojo.Result;
import ai.weixiu.pojo.query.MultimodalSearchQuery;
import ai.weixiu.pojo.vo.DiagnosisPathVO;
import ai.weixiu.service.GraphQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/weixiu/path")
@AllArgsConstructor
@Tag(name="路径")
public class PathController {
    private final GraphQueryService graphQueryService;

    @GetMapping("/page")
    @Operation(summary = "分页获取诊断路径,一次获取10个")
    public Result<PageResult<DiagnosisPathVO>> getDiagnosisPaths(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String componentDescription,
            @RequestParam(required = false) String faultDescription,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.success(graphQueryService.findDiagnosisPaths(keyword,componentDescription, faultDescription, page, size));
    }

    @PostMapping("/multimodal-search")
    @Operation(summary = "通过文字+图片多模态融合检索诊断路径")
    public Result<PageResult<DiagnosisPathVO>> searchByMultimodal(@RequestBody MultimodalSearchQuery query) {
        return Result.success(graphQueryService.findDiagnosisPathsByMultimodal(query));
    }
}
