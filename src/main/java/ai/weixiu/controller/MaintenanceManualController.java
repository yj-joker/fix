package ai.weixiu.controller;

import ai.weixiu.entity.MaintenanceManual;
import ai.weixiu.pojo.PageResult;
import ai.weixiu.pojo.Result;
import ai.weixiu.pojo.dto.MaintenanceManualDTO;
import ai.weixiu.pojo.query.MaintenanceManualQuery;
import ai.weixiu.service.MaintenanceManualService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/weixiu/maintenance-manual")
@AllArgsConstructor
@Tag(name = "维修手册管理")
public class MaintenanceManualController {
    private final MaintenanceManualService maintenanceManualService;

    @PostMapping("/save")
    @Operation(summary = "新增维修手册")
    public Result<MaintenanceManual> save(@ModelAttribute MaintenanceManualDTO maintenanceManualDTO,
                                          @RequestParam("file") MultipartFile file) {
        return Result.success(maintenanceManualService.add(maintenanceManualDTO, file));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "根据 ID 删除维修手册")
    public Result deleteById(@PathVariable Long id) {
        maintenanceManualService.deleteById(id);
        return Result.success();
    }

    @PutMapping("/update")
    @Operation(summary = "更新维修手册")
    public Result<MaintenanceManual> update(@ModelAttribute MaintenanceManualDTO maintenanceManualDTO,
                                            @RequestParam(value = "file", required = false) MultipartFile file) {
        return Result.success(maintenanceManualService.update(maintenanceManualDTO, file));
    }

    @GetMapping("/{id}")
    @Operation(summary = "根据 ID 查询维修手册")
    public Result<MaintenanceManual> getById(@PathVariable Long id) {
        return Result.success(maintenanceManualService.getManualById(id));
    }

    @PostMapping("/list")
    @Operation(summary = "分页查询维修手册")
    public Result<PageResult<MaintenanceManual>> list(@RequestBody MaintenanceManualQuery query) {
        return Result.success(maintenanceManualService.getManualList(query));
    }
}
