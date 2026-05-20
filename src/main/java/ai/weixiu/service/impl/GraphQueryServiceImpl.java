package ai.weixiu.service.impl;

import ai.weixiu.pojo.PageResult;
import ai.weixiu.pojo.query.DiagnosisSearchQuery;
import ai.weixiu.pojo.vo.ComponentVO;
import ai.weixiu.pojo.vo.DeviceVO;
import ai.weixiu.pojo.vo.DiagnosisPathVO;
import ai.weixiu.pojo.vo.FaultVO;
import ai.weixiu.repository.DeviceRepository;
import ai.weixiu.service.ComponentService;
import ai.weixiu.service.FaultService;
import ai.weixiu.service.GraphQueryService;
import ai.weixiu.utils.MultimodalEmbeddingUtils;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 统一诊断路径查询服务
 * <p>
 * 设计原则：向量做召回，图谱做推理，各查各的，ID 层面合并。
 * <p>
 * 流程：
 * 1. keyword → Device 模糊匹配 → deviceIds
 * 2. faultDescription → 文本向量(1536维) → 只搜 fault_embedding_index → faultIds
 * 3. componentDescription → 文本向量(1536维) → 只搜 component_embedding_index → componentIds
 * 4. imageUrls → 图片向量(1024维，不融合文字) → 搜 fault_multimodal_index + component_multimodal_index
 * 5. 合并去重（同ID取最高分）
 * 6. OR Cypher + matchScore 多维度评分排序 → 分页返回
 */
@Service
@AllArgsConstructor
@Slf4j
public class GraphQueryServiceImpl implements GraphQueryService {

    private final Neo4jClient neo4jClient;
    private final DeviceRepository deviceRepository;
    private final FaultService faultService;
    private final ComponentService componentService;
    private final MultimodalEmbeddingUtils multimodalEmbeddingUtils;

    @Override
    public PageResult<DiagnosisPathVO> searchDiagnosisPaths(DiagnosisSearchQuery query) {
        int safePage = Math.max(query.getPage(), 0);
        int safeSize = Math.max(query.getSize(), 5);
        int skip = safePage * safeSize;
        double minScore = query.getMinScore();
        long searchLimit = 10L;

        boolean hasKeyword = hasText(query.getKeyword());
        boolean hasFaultDesc = hasText(query.getFaultDescription());
        boolean hasCompDesc = hasText(query.getComponentDescription());
        boolean hasImages = query.getImageUrls() != null && !query.getImageUrls().isEmpty();

        if (!hasFaultDesc && !hasCompDesc && !hasImages) {
            return emptyResult(safePage, safeSize);
        }

        // ===== 1. 设备模糊匹配（top 10）=====
        List<String> deviceIds = null;
        if (hasKeyword) {
            List<DeviceVO> devices = deviceRepository.getDevices(query.getKeyword(), 0, 10);
            if (!devices.isEmpty()) {
                deviceIds = devices.stream().map(DeviceVO::getId).toList();
            }
        }

        // ===== 2. 故障文本向量检索（只搜 fault 索引）=====
        Map<String, Double> faultScoreMap = new HashMap<>();
        if (hasFaultDesc) {
            List<FaultVO> faults = faultService.getFaultByEmbedding(query.getFaultDescription(), searchLimit, minScore);
            for (FaultVO f : faults) {
                faultScoreMap.merge(f.getId(), f.getScore(), Math::max);
            }
        }

        // ===== 3. 部件文本向量检索（只搜 component 索引）=====
        Map<String, Double> compScoreMap = new HashMap<>();
        if (hasCompDesc) {
            List<ComponentVO> components = componentService.getComponentByEmbedding(query.getComponentDescription(), searchLimit, minScore);
            for (ComponentVO c : components) {
                compScoreMap.merge(c.getId(), c.getScore(), Math::max);
            }
        }

        // ===== 4. 图片向量检索（纯图片，不融合文字，搜两个多模态索引）=====
        if (hasImages) {
            List<Double> imageVector = multimodalEmbeddingUtils.getMultimodalEmbedding(null, query.getImageUrls());
            if (imageVector != null && !imageVector.isEmpty()) {
                List<FaultVO> imgFaults = faultService.getFaultByMultimodalEmbedding(imageVector, searchLimit, minScore);
                for (FaultVO f : imgFaults) {
                    faultScoreMap.merge(f.getId(), f.getScore(), Math::max);
                }
                List<ComponentVO> imgComps = componentService.getComponentByMultimodalEmbedding(imageVector, searchLimit, minScore);
                for (ComponentVO c : imgComps) {
                    compScoreMap.merge(c.getId(), c.getScore(), Math::max);
                }
            }
        }

        // ===== 5. 检查召回结果 =====
        List<String> faultIds = faultScoreMap.isEmpty() ? null : new ArrayList<>(faultScoreMap.keySet());
        List<String> componentIds = compScoreMap.isEmpty() ? null : new ArrayList<>(compScoreMap.keySet());

        if (faultIds == null && componentIds == null) {
            return emptyResult(safePage, safeSize);
        }

        // ===== 6. OR Cypher + matchScore 排序 =====
        List<DiagnosisPathVO> records = queryPathsWithScoring(deviceIds, componentIds, faultIds, skip, safeSize);
        Long total = queryPathsTotal(componentIds, faultIds);

        // ===== 7. 补充向量分数和路径文本 =====
        for (DiagnosisPathVO vo : records) {
            vo.setFaultScore(faultScoreMap.get(vo.getFaultId()));
            vo.setComponentScore(compScoreMap.get(vo.getComponentId()));
            vo.setPathText(buildPathText(vo));
        }

        log.info("诊断路径查询: keyword={} faultIds={} compIds={} imageCount={} found={}",
                query.getKeyword(),
                faultIds != null ? faultIds.size() : 0,
                componentIds != null ? componentIds.size() : 0,
                hasImages ? query.getImageUrls().size() : 0,
                records.size());

        return pageResult(records, total, safePage, safeSize);
    }

    // ===== 核心 Cypher：OR 匹配 + matchScore 评分 =====

    /**
     * OR 条件召回 + 多维度评分排序。
     * <p>
     * - faultIds 和 componentIds 用 OR 连接（任一匹配即召回）
     * - deviceIds 作为额外加分项（不强制过滤）
     * - matchScore = fault匹配(+1) + comp匹配(+1) + device匹配(+1) + 历史故障(+1)
     */
    private List<DiagnosisPathVO> queryPathsWithScoring(
            List<String> deviceIds,
            List<String> componentIds,
            List<String> faultIds,
            int skip,
            int limit
    ) {
        Map<String, Object> params = new HashMap<>();
        params.put("skip", skip);
        params.put("limit", limit);

        // 构建 OR 条件
        List<String> orConditions = new ArrayList<>();
        if (componentIds != null && !componentIds.isEmpty()) {
            orConditions.add("c.id IN $componentIds");
            params.put("componentIds", componentIds);
        }
        if (faultIds != null && !faultIds.isEmpty()) {
            orConditions.add("f.id IN $faultIds");
            params.put("faultIds", faultIds);
        }

        String whereClause = String.join(" OR ", orConditions);

        // 确保评分参数存在（即使为空列表）
        params.putIfAbsent("componentIds", List.of());
        params.putIfAbsent("faultIds", List.of());
        params.put("deviceIds", deviceIds != null ? deviceIds : List.of());

        String cypher = """
                MATCH (d:Device)-[:OWNS]->(c:Component)-[:CAUSES]->(f:Fault)
                WHERE %s
                OPTIONAL MATCH (f)-[:HAS_SOLUTION]->(s:Solution)
                OPTIONAL MATCH (d)-[hf:HAS_FAULT]->(f)
                WITH d, c, f, hf IS NOT NULL AS hasHistory,
                     CASE WHEN f.id IN $faultIds THEN 1 ELSE 0 END +
                     CASE WHEN c.id IN $componentIds THEN 1 ELSE 0 END +
                     CASE WHEN d.id IN $deviceIds THEN 1 ELSE 0 END +
                     CASE WHEN hf IS NOT NULL THEN 1 ELSE 0 END AS matchScore,
                     collect(DISTINCT {
                         id: s.id,
                         title: s.title,
                         estimatedTime: s.estimated_time,
                         verified: s.verified
                     }) AS solutions
                ORDER BY matchScore DESC, hasHistory DESC
                SKIP $skip
                LIMIT $limit
                RETURN d.id AS deviceId,
                       d.name AS deviceName,
                       c.id AS componentId,
                       c.name AS componentName,
                       f.id AS faultId,
                       f.name AS faultName,
                       f.severity AS faultSeverity,
                       hasHistory,
                       matchScore,
                       solutions
                """.formatted(whereClause);

        return neo4jClient.query(cypher)
                .bindAll(params)
                .fetchAs(DiagnosisPathVO.class)
                .mappedBy((ctx, record) -> mapAggregatedPath(record))
                .all()
                .stream()
                .toList();
    }

    private Long queryPathsTotal(List<String> componentIds, List<String> faultIds) {
        Map<String, Object> params = new HashMap<>();

        List<String> orConditions = new ArrayList<>();
        if (componentIds != null && !componentIds.isEmpty()) {
            orConditions.add("c.id IN $componentIds");
            params.put("componentIds", componentIds);
        }
        if (faultIds != null && !faultIds.isEmpty()) {
            orConditions.add("f.id IN $faultIds");
            params.put("faultIds", faultIds);
        }

        String whereClause = String.join(" OR ", orConditions);

        String cypher = """
                MATCH (d:Device)-[:OWNS]->(c:Component)-[:CAUSES]->(f:Fault)
                WHERE %s
                RETURN count(DISTINCT [d.id, c.id, f.id]) AS total
                """.formatted(whereClause);

        return neo4jClient.query(cypher)
                .bindAll(params)
                .fetchAs(Long.class)
                .first()
                .orElse(0L);
    }

    // ===== 映射方法 =====

    private DiagnosisPathVO mapAggregatedPath(org.neo4j.driver.Record record) {
        DiagnosisPathVO vo = new DiagnosisPathVO();
        vo.setDeviceId(record.get("deviceId").asString(null));
        vo.setDeviceName(record.get("deviceName").asString(null));
        vo.setComponentId(record.get("componentId").asString(null));
        vo.setComponentName(record.get("componentName").asString(null));
        vo.setFaultId(record.get("faultId").asString(null));
        vo.setFaultName(record.get("faultName").asString(null));
        vo.setFaultSeverity(record.get("faultSeverity").asString(null));
        vo.setMatchScore(record.get("matchScore").asInt(0));

        // 解析聚合的 solutions 列表
        List<DiagnosisPathVO.SolutionBrief> solutions = new ArrayList<>();
        var solutionNodes = record.get("solutions").asList();
        for (Object obj : solutionNodes) {
            if (obj instanceof Map<?, ?> map) {
                Object id = map.get("id");
                if (id == null) continue;
                solutions.add(new DiagnosisPathVO.SolutionBrief(
                        id.toString(),
                        map.get("title") != null ? map.get("title").toString() : null,
                        map.get("estimatedTime") != null ? ((Number) map.get("estimatedTime")).intValue() : null,
                        map.get("verified") != null ? (Boolean) map.get("verified") : null
                ));
            }
        }

        // 排序：verified DESC, estimatedTime ASC
        solutions.sort((a, b) -> {
            int v = Boolean.compare(b.getVerified() != null && b.getVerified(),
                    a.getVerified() != null && a.getVerified());
            if (v != 0) return v;
            int ea = a.getEstimatedTime() != null ? a.getEstimatedTime() : Integer.MAX_VALUE;
            int eb = b.getEstimatedTime() != null ? b.getEstimatedTime() : Integer.MAX_VALUE;
            return Integer.compare(ea, eb);
        });

        vo.setSolutions(solutions);

        // 兼容旧字段：取排序后第一个 Solution
        if (!solutions.isEmpty()) {
            DiagnosisPathVO.SolutionBrief best = solutions.get(0);
            vo.setSolutionId(best.getId());
            vo.setSolutionTitle(best.getTitle());
            vo.setEstimatedTime(best.getEstimatedTime());
            vo.setVerified(best.getVerified());
        }

        return vo;
    }

    // ===== 辅助方法 =====

    private String buildPathText(DiagnosisPathVO vo) {
        StringBuilder sb = new StringBuilder();

        if (hasText(vo.getDeviceName())) {
            sb.append(vo.getDeviceName());
        }
        if (hasText(vo.getComponentName())) {
            if (!sb.isEmpty()) sb.append(" -> OWNS -> ");
            sb.append(vo.getComponentName());
        }
        if (hasText(vo.getFaultName())) {
            if (!sb.isEmpty()) sb.append(" -> CAUSES -> ");
            sb.append(vo.getFaultName());
        }
        if (hasText(vo.getSolutionTitle())) {
            sb.append(" -> HAS_SOLUTION -> ").append(vo.getSolutionTitle());
        }
        return sb.toString();
    }

    private PageResult<DiagnosisPathVO> emptyResult(int page, int size) {
        return pageResult(List.of(), 0L, page, size);
    }

    private PageResult<DiagnosisPathVO> pageResult(List<DiagnosisPathVO> records, Long total, int page, int size) {
        PageResult<DiagnosisPathVO> result = new PageResult<>();
        result.setRecords(records);
        result.setTotal(total);
        result.setPage(page);
        result.setSize(size);
        return result;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
