package ai.weixiu.service.impl;

import ai.weixiu.pojo.PageResult;
import ai.weixiu.pojo.query.MultimodalSearchQuery;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
@Slf4j
public class GraphQueryServiceImpl implements GraphQueryService {

    private final Neo4jClient neo4jClient;
    private final DeviceRepository deviceRepository;
    private final FaultService faultService;
    private final ComponentService componentService;
    private final MultimodalEmbeddingUtils multimodalEmbeddingUtils;

    /*
     * 根据设备线索、部件线索、故障描述，分页查询 RAG 图谱证据链。
     * 通用查询：动态拼接 WHERE 子句，collect() 聚合 Solution 消除行膨胀。
     */
    @Override
    public PageResult<DiagnosisPathVO> findDiagnosisPaths(
            String keyword,
            String componentDescription,
            String faultDescription,
            int page,
            int size
    ) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(size, 5);
        int skip = safePage * safeSize;

        if (!hasText(faultDescription) && !hasText(componentDescription)) {
            return emptyResult(safePage, safeSize);
        }

        // 1. 设备匹配（限制 top 10）
        List<String> deviceIds = null;
        if (hasText(keyword)) {
            List<DeviceVO> devices = deviceRepository.getDevices(keyword, 0, 10);
            if (!devices.isEmpty()) {
                deviceIds = devices.stream().map(DeviceVO::getId).toList();
            }
        }

        // 2. 故障向量匹配
        List<String> faultIds = null;
        Map<String, Double> faultScoreMap = Map.of();
        if (hasText(faultDescription)) {
            List<FaultVO> faults = faultService.getFaultByEmbedding(faultDescription, 10L, 0.70);
            if (!faults.isEmpty()) {
                faultIds = faults.stream().map(FaultVO::getId).toList();
                faultScoreMap = faults.stream()
                        .collect(Collectors.toMap(FaultVO::getId, FaultVO::getScore, Math::max));
            }
        }

        // 3. 部件向量匹配
        List<String> componentIds = null;
        Map<String, Double> componentScoreMap = Map.of();
        if (hasText(componentDescription)) {
            List<ComponentVO> components = componentService.getComponentByEmbedding(componentDescription, 10L, 0.70);
            if (!components.isEmpty()) {
                componentIds = components.stream().map(ComponentVO::getId).toList();
                componentScoreMap = components.stream()
                        .collect(Collectors.toMap(ComponentVO::getId, ComponentVO::getScore, Math::max));
            }
        }

        boolean hasFault = faultIds != null;
        boolean hasComponent = componentIds != null;
        if (!hasFault && !hasComponent) {
            return emptyResult(safePage, safeSize);
        }

        // 4. 通用路径查询
        List<DiagnosisPathVO> records = queryPaths(deviceIds, componentIds, faultIds, skip, safeSize);
        Long total = queryPathsTotal(deviceIds, componentIds, faultIds);

        // 5. 补充分数和路径文本
        for (DiagnosisPathVO vo : records) {
            vo.setFaultScore(faultScoreMap.get(vo.getFaultId()));
            vo.setComponentScore(componentScoreMap.get(vo.getComponentId()));
            vo.setPathText(buildPathText(vo));
        }

        log.info("诊断路径查询: keyword={} faults={} components={} found={}",
                keyword, faultIds != null ? faultIds.size() : 0,
                componentIds != null ? componentIds.size() : 0, records.size());
        return pageResult(records, total, safePage, safeSize);
    }

    @Override
    public PageResult<DiagnosisPathVO> findDiagnosisPathsByMultimodal(MultimodalSearchQuery query) {
        // 1. 调 Python 拿融合向量（文字 + 图片）
        List<Double> vector = multimodalEmbeddingUtils.getMultimodalEmbedding(
            query.getText(), query.getImageUrls()
        );
        if (vector == null || vector.isEmpty()) {
            return emptyResult(0, query.getLimit());
        }

        // 2. 用融合向量在 Fault 的 multimodalEmbedding 索引中检索
        List<DiagnosisPathVO> records = neo4jClient.query("""
                CALL db.index.vector.queryNodes('fault_multimodal_index', $limit, $vector)
                YIELD node AS f, score
                WHERE score >= $minScore
                OPTIONAL MATCH (c:Component)-[:CAUSES]->(f)
                OPTIONAL MATCH (f)-[:HAS_SOLUTION]->(s:Solution)
                OPTIONAL MATCH (d:Device)-[:OWNS]->(c)
                RETURN d.id AS deviceId,
                       d.name AS deviceName,
                       c.id AS componentId,
                       c.name AS componentName,
                       f.id AS faultId,
                       f.name AS faultName,
                       f.severity AS faultSeverity,
                       f.image_urls AS faultImageUrls,
                       s.id AS solutionId,
                       s.title AS solutionTitle,
                       s.estimated_time AS estimatedTime,
                       s.verified AS verified,
                       score
                ORDER BY score DESC, s.verified DESC
                LIMIT $limit
                """)
                .bind(vector).to("vector")
                .bind(query.getLimit()).to("limit")
                .bind(query.getMinScore()).to("minScore")
                .fetchAs(DiagnosisPathVO.class)
                .mappedBy((ctx, record) -> {
                    DiagnosisPathVO vo = mapDiagnosisPath(record);
                    vo.setFaultScore(record.get("score").isNull() ? null : record.get("score").asDouble());
                    if (!record.get("faultImageUrls").isNull()) {
                        vo.setFaultImageUrls(record.get("faultImageUrls").asList(org.neo4j.driver.Value::asString));
                    }
                    return vo;
                })
                .all()
                .stream()
                .toList();

        for (DiagnosisPathVO vo : records) {
            vo.setPathText(buildPathText(vo));
        }

        return pageResult(records, (long) records.size(), 0, query.getLimit());
    }

    // ===== 通用查询方法 =====

    /**
     * 通用诊断路径查询 — 根据有效的 ID 列表动态拼接 WHERE 子句。
     * Solution 用 collect() 聚合，消除行膨胀。
     * 分页基于 (device, component, fault) 三元组。
     */
    private List<DiagnosisPathVO> queryPaths(
            List<String> deviceIds,
            List<String> componentIds,
            List<String> faultIds,
            int skip,
            int limit
    ) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();

        if (deviceIds != null && !deviceIds.isEmpty()) {
            conditions.add("d.id IN $deviceIds");
            params.put("deviceIds", deviceIds);
        }
        if (componentIds != null && !componentIds.isEmpty()) {
            conditions.add("c.id IN $componentIds");
            params.put("componentIds", componentIds);
        }
        if (faultIds != null && !faultIds.isEmpty()) {
            conditions.add("f.id IN $faultIds");
            params.put("faultIds", faultIds);
        }

        String whereClause = conditions.isEmpty() ? "true" : String.join(" AND ", conditions);
        params.put("skip", skip);
        params.put("limit", limit);

        String cypher = """
                MATCH (d:Device)-[:OWNS]->(c:Component)-[:CAUSES]->(f:Fault)
                WHERE %s
                OPTIONAL MATCH (f)-[:HAS_SOLUTION]->(s:Solution)
                OPTIONAL MATCH (d)-[hf:HAS_FAULT]->(f)
                WITH d, c, f, hf IS NOT NULL AS hasHistory,
                     collect(DISTINCT {
                         id: s.id,
                         title: s.title,
                         estimatedTime: s.estimated_time,
                         verified: s.verified
                     }) AS solutions
                ORDER BY hasHistory DESC
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

    private Long queryPathsTotal(
            List<String> deviceIds,
            List<String> componentIds,
            List<String> faultIds
    ) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();

        if (deviceIds != null && !deviceIds.isEmpty()) {
            conditions.add("d.id IN $deviceIds");
            params.put("deviceIds", deviceIds);
        }
        if (componentIds != null && !componentIds.isEmpty()) {
            conditions.add("c.id IN $componentIds");
            params.put("componentIds", componentIds);
        }
        if (faultIds != null && !faultIds.isEmpty()) {
            conditions.add("f.id IN $faultIds");
            params.put("faultIds", faultIds);
        }

        String whereClause = conditions.isEmpty() ? "true" : String.join(" AND ", conditions);

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

    /**
     * 映射聚合后的路径记录（collect 后的 solutions 列表）。
     */
    private DiagnosisPathVO mapAggregatedPath(org.neo4j.driver.Record record) {
        DiagnosisPathVO vo = new DiagnosisPathVO();
        vo.setDeviceId(record.get("deviceId").asString(null));
        vo.setDeviceName(record.get("deviceName").asString(null));
        vo.setComponentId(record.get("componentId").asString(null));
        vo.setComponentName(record.get("componentName").asString(null));
        vo.setFaultId(record.get("faultId").asString(null));
        vo.setFaultName(record.get("faultName").asString(null));
        vo.setFaultSeverity(record.get("faultSeverity").asString(null));

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

        // 按 verified DESC, estimatedTime ASC 排序
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

    /**
     * 旧映射方法 — 供 findDiagnosisPathsByMultimodal 继续使用（Task 3 会重构）。
     */
    private DiagnosisPathVO mapDiagnosisPath(org.neo4j.driver.Record record) {
        DiagnosisPathVO vo = new DiagnosisPathVO();
        vo.setDeviceId(record.get("deviceId").asString(null));
        vo.setDeviceName(record.get("deviceName").asString(null));
        vo.setComponentId(record.get("componentId").asString(null));
        vo.setComponentName(record.get("componentName").asString(null));
        vo.setFaultId(record.get("faultId").asString(null));
        vo.setFaultName(record.get("faultName").asString(null));
        vo.setFaultSeverity(record.get("faultSeverity").asString(null));
        vo.setSolutionId(record.get("solutionId").asString(null));
        vo.setSolutionTitle(record.get("solutionTitle").asString(null));
        vo.setEstimatedTime(record.get("estimatedTime").isNull() ? null : record.get("estimatedTime").asInt());
        vo.setVerified(record.get("verified").isNull() ? null : record.get("verified").asBoolean());
        return vo;
    }

    // ===== 辅助方法 =====

    private String buildPathText(DiagnosisPathVO vo) {
        StringBuilder sb = new StringBuilder();

        if (hasText(vo.getDeviceName())) {
            sb.append(vo.getDeviceName());
        }

        if (hasText(vo.getComponentName())) {
            if (!sb.isEmpty()) {
                sb.append(" -> OWNS -> ");
            }
            sb.append(vo.getComponentName());
        }

        if (hasText(vo.getFaultName())) {
            if (!sb.isEmpty()) {
                sb.append(" -> CAUSES -> ");
            }
            sb.append(vo.getFaultName());
        }

        if (hasText(vo.getSolutionTitle())) {
            sb.append(" -> HAS_SOLUTION -> ")
                    .append(vo.getSolutionTitle());
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
