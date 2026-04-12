package ai.weixiu.controller;

import ai.weixiu.pojo.Result;
import ai.weixiu.entity.Device;
import ai.weixiu.service.DeviceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/weixiu/device")
@AllArgsConstructor
@Tag(name = "设备管理")
public class DeviceController {

    private final DeviceService deviceService;

    @PostMapping
    @Operation(summary = "新增设备")
    public Result<Device> save(@RequestBody Device device) {
        return Result.success(deviceService.save(device));
    }

    @GetMapping("/{id}")
    @Operation(summary = "根据 ID 查询设备")
    public Result<Device> findById(@PathVariable String id) {
        return deviceService.findById(id)
                .map(Result::success)
                .orElse(Result.error("404", "设备不存在"));
    }

    @GetMapping("/list")
    @Operation(summary = "查询所有设备")
    public Result<List<Device>> findAll() {
        return Result.success(deviceService.findAll());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "根据 ID 删除设备")
    public Result deleteById(@PathVariable String id) {
        deviceService.deleteById(id);
        return Result.success();
    }

    @PutMapping
    @Operation(summary = "更新设备信息")
    public Result<Device> update(@RequestBody Device device) {
        return Result.success(deviceService.update(device));
    }
}
