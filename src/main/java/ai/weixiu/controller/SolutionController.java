package ai.weixiu.controller;

import ai.weixiu.pojo.Result;
import ai.weixiu.entity.Solution;
import ai.weixiu.service.SolutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/weixiu/solution")
@AllArgsConstructor
@Tag(name = "解决方案管理")
public class SolutionController {

    private final SolutionService solutionService;

    @PostMapping
    @Operation(summary = "新增解决方案")
    public Result<Solution> save(@RequestBody Solution solution) {
        return Result.success(solutionService.save(solution));
    }

    @GetMapping("/{id}")
    @Operation(summary = "根据 ID 查询解决方案")
    public Result<Solution> findById(@PathVariable String id) {
        return solutionService.findById(id)
                .map(Result::success)
                .orElse(Result.error("404", "解决方案不存在"));
    }

    @GetMapping("/list")
    @Operation(summary = "查询所有解决方案")
    public Result<List<Solution>> findAll() {
        return Result.success(solutionService.findAll());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "根据 ID 删除解决方案")
    public Result deleteById(@PathVariable String id) {
        solutionService.deleteById(id);
        return Result.success();
    }

    @PutMapping
    @Operation(summary = "更新解决方案信息")
    public Result<Solution> update(@RequestBody Solution solution) {
        return Result.success(solutionService.update(solution));
    }
}
