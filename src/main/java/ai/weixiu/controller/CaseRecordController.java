package ai.weixiu.controller;

import ai.weixiu.pojo.Result;
import ai.weixiu.pojo.entity.CaseRecord;
import ai.weixiu.service.CaseRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/weixiu/case-record")
@AllArgsConstructor
@Tag(name = "案例记录管理")
public class CaseRecordController {

    private final CaseRecordService caseRecordService;

    @PostMapping
    @Operation(summary = "新增案例记录")
    public Result<CaseRecord> save(@RequestBody CaseRecord caseRecord) {
        return Result.success(caseRecordService.save(caseRecord));
    }

    @GetMapping("/{id}")
    @Operation(summary = "根据 ID 查询案例记录")
    public Result<CaseRecord> findById(@PathVariable String id) {
        return caseRecordService.findById(id)
                .map(Result::success)
                .orElse(Result.error("404", "案例记录不存在"));
    }

    @GetMapping("/list")
    @Operation(summary = "查询所有案例记录")
    public Result<List<CaseRecord>> findAll() {
        return Result.success(caseRecordService.findAll());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "根据 ID 删除案例记录")
    public Result deleteById(@PathVariable String id) {
        caseRecordService.deleteById(id);
        return Result.success();
    }

    @PutMapping
    @Operation(summary = "更新案例记录信息")
    public Result<CaseRecord> update(@RequestBody CaseRecord caseRecord) {
        return Result.success(caseRecordService.update(caseRecord));
    }
}
