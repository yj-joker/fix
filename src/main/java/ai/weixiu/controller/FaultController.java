package ai.weixiu.controller;

import ai.weixiu.pojo.Result;
import ai.weixiu.entity.Fault;
import ai.weixiu.service.FaultService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/weixiu/fault")
@AllArgsConstructor
@Tag(name = "故障管理")
public class FaultController {

    private final FaultService faultService;

    @PostMapping
    @Operation(summary = "新增故障")
    public Result<Fault> save(@RequestBody Fault fault) {
        return Result.success(faultService.save(fault));
    }

    @GetMapping("/{id}")
    @Operation(summary = "根据 ID 查询故障")
    public Result<Fault> findById(@PathVariable String id) {
        return faultService.findById(id)
                .map(Result::success)
                .orElse(Result.error("404", "故障不存在"));
    }

    @GetMapping("/list")
    @Operation(summary = "查询所有故障")
    public Result<List<Fault>> findAll() {
        return Result.success(faultService.findAll());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "根据 ID 删除故障")
    public Result deleteById(@PathVariable String id) {
        faultService.deleteById(id);
        return Result.success();
    }

    @PutMapping
    @Operation(summary = "更新故障信息")
    public Result<Fault> update(@RequestBody Fault fault) {
        return Result.success(faultService.update(fault));
    }
}
