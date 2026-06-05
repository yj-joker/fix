package ai.weixiu.controller;

import ai.weixiu.entity.CaseRecord;
import ai.weixiu.pojo.Result;
import ai.weixiu.pojo.dto.CaseRecordDTO;
import ai.weixiu.pojo.vo.CaseRecordVO;
import ai.weixiu.service.CaseRecordService;
import ai.weixiu.utils.VoConverter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/weixiu/case-record")
@AllArgsConstructor
@Tag(name = "案例记录管理")
public class CaseRecordController {

    private final CaseRecordService caseRecordService;

    @PostMapping("/save")
    @Operation(summary = "新增案例记录")
    public Result<CaseRecordVO> save(@RequestBody CaseRecordDTO caseRecordDTO) {
        return Result.success(VoConverter.convert(caseRecordService.save(caseRecordDTO), CaseRecordVO.class));
    }

    @GetMapping("/{id}")
    @Operation(summary = "根据 ID 查询案例记录")
    public Result<CaseRecordVO> findById(@PathVariable String id) {
        return Result.success(VoConverter.convert(caseRecordService.findById(id).get(), CaseRecordVO.class));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "根据 ID 删除案例记录")
    public Result deleteById(@PathVariable String id) {
        caseRecordService.deleteById(id);
        return Result.success();
    }

    @PutMapping("/update")
    @Operation(summary = "更新案例记录信息")
    public Result<CaseRecordVO> update(@RequestBody CaseRecordDTO caseRecordDTO) {
        return Result.success(VoConverter.convert(caseRecordService.update(caseRecordDTO), CaseRecordVO.class));
    }
}
