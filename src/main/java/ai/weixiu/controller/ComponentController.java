package ai.weixiu.controller;

import ai.weixiu.entity.Component;
import ai.weixiu.pojo.PageResult;
import ai.weixiu.pojo.Result;
import ai.weixiu.pojo.dto.ComponentDTO;
import ai.weixiu.pojo.query.ComponentQuery;
import ai.weixiu.pojo.vo.ComponentVO;
import ai.weixiu.pojo.vo.FaultVO;
import ai.weixiu.service.ComponentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/weixiu/component")
@AllArgsConstructor
@Tag(name = "部件管理")
public class ComponentController {

    private final ComponentService componentService;

    @PostMapping("/save")
    @Operation(summary = "新增部件")
    public Result<Component> save(@RequestBody ComponentDTO componentDTO) {
        return Result.success(componentService.save(componentDTO));
    }

    @GetMapping("/{id}")
    @Operation(summary = "根据 ID 查询部件")
    public Result<Component> findById(@PathVariable String id) {
        return Result.success(componentService.findById(id).get());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "根据 ID 删除部件")
    public Result deleteById(@PathVariable String id) {
        componentService.deleteById(id);
        return Result.success();
    }

    @PutMapping("/update")
    @Operation(summary = "更新部件信息")
    public Result<Component> update(@RequestBody ComponentDTO componentDTO) {
        return Result.success(componentService.update(componentDTO));
    }
    @GetMapping("/faults")
    @Operation(summary = "查询部件的故障")
    public Result<PageResult<FaultVO>> getComponentFaults(@RequestBody ComponentQuery componentQuery) {
        return Result.success(componentService.getComponentFaults(componentQuery));
    }
    @GetMapping("/getComponentByEmbedding")
    @Operation(summary = "根据嵌入向量查询部件")
    public Result<List<ComponentVO>> getComponentByEmbedding(String description, Long limit, Double minScore ) {
        return Result.success(componentService.getComponentByEmbedding(description, limit, minScore));
    }
}
