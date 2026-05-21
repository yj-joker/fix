package ai.weixiu.controller;

import ai.weixiu.entity.MaintenanceManual;
import ai.weixiu.pojo.PageResult;
import ai.weixiu.pojo.Result;
import ai.weixiu.pojo.dto.MaintenanceManualDTO;
import ai.weixiu.pojo.dto.MaintenanceManualReadHeartbeatDTO;
import ai.weixiu.pojo.dto.MaintenanceManualReadStartDTO;
import ai.weixiu.pojo.query.MaintenanceManualQuery;
import ai.weixiu.pojo.vo.MaintenanceManualRankVO;
import ai.weixiu.pojo.vo.MaintenanceManualReadHeartbeatVO;
import ai.weixiu.pojo.vo.MaintenanceManualReadStartVO;
import ai.weixiu.pojo.vo.MaintenanceManualVO;
import ai.weixiu.enumerate.MaintenanceManualRankType;
import ai.weixiu.service.MaintenanceManualService;
import ai.weixiu.service.MaintenanceManualRankService;
import ai.weixiu.service.MaintenanceManualReadService;
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

import java.util.List;

@RestController
@RequestMapping("/weixiu/maintenance-manual")
@AllArgsConstructor
@Tag(name = "维修手册管理")
public class MaintenanceManualController {
    private final MaintenanceManualService maintenanceManualService;
    private final MaintenanceManualReadService maintenanceManualReadService;
    private final MaintenanceManualRankService maintenanceManualRankService;

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
    public Result<MaintenanceManualVO> getById(@PathVariable Long id) {
        return Result.success(maintenanceManualService.getManualDetailById(id));
    }

    @PostMapping("/list")
    @Operation(summary = "分页查询维修手册")
    public Result<PageResult<MaintenanceManual>> list(@RequestBody MaintenanceManualQuery query) {
        return Result.success(maintenanceManualService.getManualList(query));
    }

    @PostMapping("/read/start")
    @Operation(summary = "开始阅读维修手册")
    public Result<MaintenanceManualReadStartVO> startRead(@RequestBody MaintenanceManualReadStartDTO readStartDTO) {
        return Result.success(maintenanceManualReadService.start(readStartDTO.getManualId()));
    }

    @PostMapping("/read/heartbeat")
    @Operation(summary = "上报维修手册阅读心跳")
    public Result<MaintenanceManualReadHeartbeatVO> heartbeat(@RequestBody MaintenanceManualReadHeartbeatDTO heartbeatDTO) {
        return Result.success(maintenanceManualReadService.heartbeat(heartbeatDTO.getReadSessionId()));
    }

    @GetMapping("/rank")
    @Operation(summary = "查询维修手册排行榜")
    public Result<List<MaintenanceManualRankVO>> rank(@RequestParam(defaultValue = "day") String type,
                                                      @RequestParam(defaultValue = "10") Integer limit) {
        return Result.success(maintenanceManualRankService.getRankList(MaintenanceManualRankType.parse(type), limit));
    }
}
