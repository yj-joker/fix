package ai.weixiu.controller;

import ai.weixiu.pojo.Result;
import ai.weixiu.pojo.dto.GraphIngestDTO;
import ai.weixiu.service.GraphIngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 手册图谱入库接口。内部接口，鉴权由 SessionInterceptor 基于 X-Internal-Token 统一处理。
 */
@RestController
@RequestMapping("/weixiu/graph")
@AllArgsConstructor
@Tag(name = "手册图谱入库")
public class GraphIngestController {
    private final GraphIngestService graphIngestService;

    @PostMapping("/ingest")
    @Operation(summary = "手册抽取候选实体入库（MERGE去重+verified=false+来源标注）")
    public Result<Integer> ingest(@RequestBody GraphIngestDTO dto,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        int n = graphIngestService.ingestFromManual(dto);
        return Result.success(n);
    }

    @GetMapping("/unverified")
    public Result<List<Map<String, Object>>> unverified(@RequestParam(defaultValue = "50") int limit) {
        return Result.success(graphIngestService.listUnverified(limit));
    }

    @PostMapping("/approve/{solutionId}")
    public Result<Void> approve(@PathVariable String solutionId) {
        graphIngestService.approveSolution(solutionId);
        return Result.success(null);
    }

    @DeleteMapping("/reject")
    public Result<Void> reject(@RequestParam String label, @RequestParam String nodeId) {
        graphIngestService.rejectNode(label, nodeId);
        return Result.success(null);
    }
}
