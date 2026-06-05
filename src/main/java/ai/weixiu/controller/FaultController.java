package ai.weixiu.controller;

import ai.weixiu.entity.Fault;
import ai.weixiu.pojo.PageResult;
import ai.weixiu.pojo.Result;
import ai.weixiu.pojo.dto.FaultDTO;
import ai.weixiu.pojo.query.FaultQuery;
import ai.weixiu.pojo.vo.FaultVO;
import ai.weixiu.pojo.vo.SolutionVO;
import ai.weixiu.service.FaultService;
import ai.weixiu.utils.VoConverter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/weixiu/fault")
@AllArgsConstructor
@Tag(name = "故障管理")
public class FaultController {

    private final FaultService faultService;

    @PostMapping("/save")
    @Operation(summary = "新增故障")
    public Result<FaultVO> save(@RequestBody FaultDTO faultDTO) {
        return Result.success(VoConverter.convert(faultService.save(faultDTO), FaultVO.class));
    }

    @GetMapping("/{id}")
    @Operation(summary = "根据 ID 查询故障")
    public Result<FaultVO> findById(@PathVariable String id) {
        return Result.success(VoConverter.convert(faultService.findById(id).get(), FaultVO.class));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "根据 ID 删除故障")
    public Result deleteById(@PathVariable String id) {
        faultService.deleteById(id);
        return Result.success();
    }

    @PutMapping("/update")
    @Operation(summary = "更新故障信息")
    public Result<FaultVO> update(@RequestBody FaultDTO faultDTO) {
        return Result.success(VoConverter.convert(faultService.update(faultDTO), FaultVO.class));
    }

    @PostMapping("/solutions")
    @Operation(summary = "分页查询故障的解决方案列表")
    public Result<PageResult<SolutionVO>> getSolutions(@RequestBody FaultQuery faultQuery) {
        return Result.success(faultService.getSolutions(faultQuery));
    }
}
