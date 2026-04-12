package ai.weixiu.controller;

import ai.weixiu.pojo.Result;
import ai.weixiu.pojo.entity.Component;
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

    @PostMapping
    @Operation(summary = "新增部件")
    public Result<Component> save(@RequestBody Component component) {
        Component saved = componentService.save(component);
        return Result.success(saved);
    }

    @GetMapping("/{id}")
    @Operation(summary = "根据 ID 查询部件")
    public Result<Component> findById(@PathVariable String id) {
        return componentService.findById(id)
                .map(Result::success)
                .orElse(Result.error("404", "部件不存在"));
    }

    @GetMapping("/list")
    @Operation(summary = "查询所有部件")
    public Result<List<Component>> findAll() {
        return Result.success(componentService.findAll());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "根据 ID 删除部件")
    public Result deleteById(@PathVariable String id) {
        componentService.deleteById(id);
        return Result.success();
    }

    @PutMapping
    @Operation(summary = "更新部件信息")
    public Result<Component> update(@RequestBody Component component) {
        return Result.success(componentService.update(component));
    }
}
