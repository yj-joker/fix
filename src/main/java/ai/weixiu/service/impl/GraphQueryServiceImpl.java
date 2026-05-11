package ai.weixiu.service.impl;

import ai.weixiu.pojo.PageResult;
import ai.weixiu.pojo.vo.ComponentVO;
import ai.weixiu.pojo.vo.DeviceVO;
import ai.weixiu.pojo.vo.DiagnosisPathVO;
import ai.weixiu.pojo.vo.FaultVO;
import ai.weixiu.repository.DeviceRepository;
import ai.weixiu.service.ComponentService;
import ai.weixiu.service.FaultService;
import ai.weixiu.service.GraphQueryService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

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

    /*
     * 根据设备线索、部件线索、故障描述，分页查询 RAG 图谱证据链。
     * 查询策略：
     *   1. 如果有设备 + 部件 + 故障：查指定设备内，指定部件导致指定故障的路径。
     *   2. 如果有部件 + 故障：查指定部件导致指定故障的全局路径。
     *   3. 如果只有部件：查该部件可能导致哪些故障以及对应方案。
     *   4. 如果有设备 + 故障：查指定设备内，哪些部件可能导致该故障。
     *   5. 如果只有故障：查全局哪些设备/部件可能关联该故障。
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

        /*
         * 如果既没有故障描述，也没有部件描述，就无法进入图谱检索。
         */
        if (!hasText(faultDescription) && !hasText(componentDescription)) {
            return emptyResult(safePage, safeSize);
        }

        /*
         * 1. 设备匹配。
         * 设备通常数量不多，可以先用名称/code/model/location 模糊查。
         * 如果 keyword 为空，则认为用户没有指定设备。
         */
        List<String> deviceIds = List.of();
        if (hasText(keyword)) {
            List<DeviceVO> devices = deviceRepository.getDevices(keyword, 0, Integer.MAX_VALUE);
            deviceIds = devices.stream()
                    .map(DeviceVO::getId)
                    .toList();
        }

        /*
         * 2. 故障向量匹配。
         * 只有 faultDescription 有值时才查。
         */
        List<FaultVO> faultByEmbedding = List.of();
        List<String> faultIds = List.of();
        Map<String, Double> faultScoreMap = Map.of();

        if (hasText(faultDescription)) {
            faultByEmbedding = faultService.getFaultByEmbedding(faultDescription, 20L, 0.80);

            faultIds = faultByEmbedding.stream()
                    .map(FaultVO::getId)
                    .toList();

            faultScoreMap = faultByEmbedding.stream()
                    .collect(Collectors.toMap(
                            FaultVO::getId,
                            FaultVO::getScore,
                            /*
                             * 防止向量检索偶然返回重复 id 时 Collectors.toMap 报错。
                             * 如果重复，保留较高分数。
                             */
                            Math::max
                    ));
        }

        /*
         * 3. 部件向量匹配。
         * 只有 componentDescription 有值时才查。
         */
        List<ComponentVO> componentByEmbedding = List.of();
        List<String> componentIds = List.of();
        Map<String, Double> componentScoreMap = Map.of();

        if (hasText(componentDescription)) {
            componentByEmbedding = componentService.getComponentByEmbedding(componentDescription, 20L, 0.50);

            componentIds = componentByEmbedding.stream()
                    .map(ComponentVO::getId)
                    .toList();

            componentScoreMap = componentByEmbedding.stream()
                    .collect(Collectors.toMap(
                            ComponentVO::getId,
                            ComponentVO::getScore,
                            Math::max
                    ));
        }

        boolean hasDevice = !deviceIds.isEmpty();
        boolean hasComponent = !componentIds.isEmpty();
        boolean hasFault = !faultIds.isEmpty();

        // 如果没有部件，或者没有故障，则无法进入图谱检索。
        if (!hasComponent && !hasFault) {
            return emptyResult(safePage, safeSize);
        }

        List<DiagnosisPathVO> records;
        Long total;

        /*
         * 分支 1：设备 + 部件 + 故障
         *   限定设备范围
         *   限定部件范围
         *   限定故障范围
         */
        if (hasDevice && hasComponent && hasFault) {
            records = getDeviceComponentFaultList(deviceIds, componentIds, faultIds, skip, safeSize);
            total = getDeviceComponentFaultTotal(deviceIds, componentIds, faultIds);
        }

        /*
         * 分支 2：部件 + 故障
         * 没有指定设备，但指定了部件。
         * 这里必须用 componentIds 约束路径，避免返回其他部件导致同一故障的方案。
         */
        else if (hasComponent && hasFault) {
            records = getComponentFaultList(componentIds, faultIds, skip, safeSize);
            total = getComponentFaultTotal(componentIds, faultIds);
        }

        /*
         * 分支 3：只有部件
         * 这时不强求 faultIds，直接查该部件可能导致哪些故障，以及这些故障的方案。
         */
        else if (hasComponent) {
            records = getComponentOnlyList(componentIds, skip, safeSize);
            total = getComponentOnlyTotal(componentIds);
        }

        /*
         * 分支 4：设备 + 故障
         * 指定设备，但没有指定部件。
         * 查该设备中哪些部件可能导致匹配到的故障。
         */
        else if (hasDevice) {
            records = getDeviceList(faultIds, deviceIds, skip, safeSize);
            total = getDeviceTotal(faultIds, deviceIds);
        }

        /*
         * 分支 5：只有故障
         *
         * 用户问题类似：
         *   “启动后抖动怎么办？”
         *
         * 没有设备、没有部件，就做全局图谱证据链检索。
         */
        else {
            records = getGlobalList(faultIds, skip, safeSize);
            total = getGlobalTotal(faultIds);
        }

        /*
         * 给证据链补充向量相似度和可读路径文本。
         * 这些字段主要给 RAG 上下文组装器使用。
         */
        for (DiagnosisPathVO vo : records) {
            vo.setFaultScore(faultScoreMap.get(vo.getFaultId()));
            vo.setComponentScore(componentScoreMap.get(vo.getComponentId()));
            vo.setPathText(buildPathText(vo));
        }

        log.info("此次找到的诊断路径: {}", records);
        return pageResult(records, total, safePage, safeSize);
    }

    /*
     * 设备 + 部件 + 故障。
     */
    private @NonNull List<DiagnosisPathVO> getDeviceComponentFaultList(
            List<String> deviceIds,
            List<String> componentIds,
            List<String> faultIds,
            int skip,
            int limit
    ) {
        return neo4jClient.query("""
                        MATCH (d:Device)-[:OWNS]->(c:Component)-[:CAUSES]->(f:Fault)
                        WHERE d.id IN $deviceIds
                          AND c.id IN $componentIds
                          AND f.id IN $faultIds
                        OPTIONAL MATCH (f)-[:HAS_SOLUTION]->(s:Solution)
                        OPTIONAL MATCH (d)-[hf:HAS_FAULT]->(f)
                        RETURN d.id AS deviceId,
                               d.name AS deviceName,
                               c.id AS componentId,
                               c.name AS componentName,
                               f.id AS faultId,
                               f.name AS faultName,
                               f.severity AS faultSeverity,
                               s.id AS solutionId,
                               s.title AS solutionTitle,
                               s.estimated_time AS estimatedTime,
                               s.verified AS verified,
                               hf IS NOT NULL AS hasHistory
                        ORDER BY hasHistory DESC, s.verified DESC, s.estimated_time ASC
                        SKIP $skip
                        LIMIT $limit
                        """)
                .bind(deviceIds).to("deviceIds")
                .bind(componentIds).to("componentIds")
                .bind(faultIds).to("faultIds")
                .bind(skip).to("skip")
                .bind(limit).to("limit")
                .fetchAs(DiagnosisPathVO.class)
                .mappedBy((ctx, record) -> mapDiagnosisPath(record))
                .all()
                .stream()
                .toList();
    }

    /*
     * 部件 + 故障。
     */
    private @NonNull List<DiagnosisPathVO> getComponentFaultList(
            List<String> componentIds,
            List<String> faultIds,
            int skip,
            int limit
    ) {
        return neo4jClient.query("""
                        MATCH (d:Device)-[:OWNS]->(c:Component)-[:CAUSES]->(f:Fault)
                        WHERE c.id IN $componentIds
                          AND f.id IN $faultIds
                        OPTIONAL MATCH (f)-[:HAS_SOLUTION]->(s:Solution)
                        OPTIONAL MATCH (d)-[hf:HAS_FAULT]->(f)
                        RETURN d.id AS deviceId,
                               d.name AS deviceName,
                               c.id AS componentId,
                               c.name AS componentName,
                               f.id AS faultId,
                               f.name AS faultName,
                               f.severity AS faultSeverity,
                               s.id AS solutionId,
                               s.title AS solutionTitle,
                               s.estimated_time AS estimatedTime,
                               s.verified AS verified,
                               hf IS NOT NULL AS hasHistory
                        ORDER BY hasHistory DESC, s.verified DESC, s.estimated_time ASC
                        SKIP $skip
                        LIMIT $limit
                        """)
                .bind(componentIds).to("componentIds")
                .bind(faultIds).to("faultIds")
                .bind(skip).to("skip")
                .bind(limit).to("limit")
                .fetchAs(DiagnosisPathVO.class)
                .mappedBy((ctx, record) -> mapDiagnosisPath(record))
                .all()
                .stream()
                .toList();
    }

    /*
     * 只有部件。
     * 查该部件可能导致的故障，以及这些故障的解决方案。
     */
    private @NonNull List<DiagnosisPathVO> getComponentOnlyList(
            List<String> componentIds,
            int skip,
            int limit
    ) {
        return neo4jClient.query("""
                        MATCH (d:Device)-[:OWNS]->(c:Component)-[:CAUSES]->(f:Fault)
                        WHERE c.id IN $componentIds
                        OPTIONAL MATCH (f)-[:HAS_SOLUTION]->(s:Solution)
                        OPTIONAL MATCH (d)-[hf:HAS_FAULT]->(f)
                        RETURN d.id AS deviceId,
                               d.name AS deviceName,
                               c.id AS componentId,
                               c.name AS componentName,
                               f.id AS faultId,
                               f.name AS faultName,
                               f.severity AS faultSeverity,
                               s.id AS solutionId,
                               s.title AS solutionTitle,
                               s.estimated_time AS estimatedTime,
                               s.verified AS verified,
                               hf IS NOT NULL AS hasHistory
                        ORDER BY hasHistory DESC, s.verified DESC, s.estimated_time ASC
                        SKIP $skip
                        LIMIT $limit
                        """)
                .bind(componentIds).to("componentIds")
                .bind(skip).to("skip")
                .bind(limit).to("limit")
                .fetchAs(DiagnosisPathVO.class)
                .mappedBy((ctx, record) -> mapDiagnosisPath(record))
                .all()
                .stream()
                .toList();
    }

    /*
     * 设备 + 故障。
     * 没有指定部件时，在指定设备拥有的所有部件中查可能原因。
     */
    private @NonNull List<DiagnosisPathVO> getDeviceList(
            List<String> faultIds,
            List<String> deviceIds,
            int skip,
            int limit
    ) {
        return neo4jClient.query("""
                        MATCH (d:Device)
                        WHERE d.id IN $deviceIds
                        MATCH (d)-[:OWNS]->(c:Component)-[:CAUSES]->(f:Fault)
                        WHERE f.id IN $faultIds
                        OPTIONAL MATCH (f)-[:HAS_SOLUTION]->(s:Solution)
                        OPTIONAL MATCH (d)-[hf:HAS_FAULT]->(f)
                        RETURN d.id AS deviceId,
                               d.name AS deviceName,
                               c.id AS componentId,
                               c.name AS componentName,
                               f.id AS faultId,
                               f.name AS faultName,
                               f.severity AS faultSeverity,
                               s.id AS solutionId,
                               s.title AS solutionTitle,
                               s.estimated_time AS estimatedTime,
                               s.verified AS verified,
                               hf IS NOT NULL AS hasHistory
                        ORDER BY hasHistory DESC, s.verified DESC, s.estimated_time ASC
                        SKIP $skip
                        LIMIT $limit
                        """)
                .bind(deviceIds).to("deviceIds")
                .bind(faultIds).to("faultIds")
                .bind(skip).to("skip")
                .bind(limit).to("limit")
                .fetchAs(DiagnosisPathVO.class)
                .mappedBy((ctx, record) -> mapDiagnosisPath(record))
                .all()
                .stream()
                .toList();
    }

    /*
     * 只有故障。
     * 全局查哪些设备/部件可能关联该故障。
     */
    private @NonNull List<DiagnosisPathVO> getGlobalList(List<String> faultIds, int skip, int limit) {
        return neo4jClient.query("""
                        MATCH (d:Device)-[:OWNS]->(c:Component)-[:CAUSES]->(f:Fault)
                        WHERE f.id IN $faultIds
                        OPTIONAL MATCH (f)-[:HAS_SOLUTION]->(s:Solution)
                        OPTIONAL MATCH (d)-[hf:HAS_FAULT]->(f)
                        RETURN d.id AS deviceId,
                               d.name AS deviceName,
                               c.id AS componentId,
                               c.name AS componentName,
                               f.id AS faultId,
                               f.name AS faultName,
                               f.severity AS faultSeverity,
                               s.id AS solutionId,
                               s.title AS solutionTitle,
                               s.estimated_time AS estimatedTime,
                               s.verified AS verified,
                               hf IS NOT NULL AS hasHistory
                        ORDER BY hasHistory DESC, s.verified DESC, s.estimated_time ASC
                        SKIP $skip
                        LIMIT $limit
                        """)
                .bind(faultIds).to("faultIds")
                .bind(skip).to("skip")
                .bind(limit).to("limit")
                .fetchAs(DiagnosisPathVO.class)
                .mappedBy((ctx, record) -> mapDiagnosisPath(record))
                .all()
                .stream()
                .toList();
    }

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

    private Long getDeviceComponentFaultTotal(
            List<String> deviceIds,
            List<String> componentIds,
            List<String> faultIds
    ) {
        return neo4jClient.query("""
                        MATCH (d:Device)-[:OWNS]->(c:Component)-[:CAUSES]->(f:Fault)
                        WHERE d.id IN $deviceIds
                          AND c.id IN $componentIds
                          AND f.id IN $faultIds
                        OPTIONAL MATCH (f)-[:HAS_SOLUTION]->(s:Solution)
                        RETURN count(*) AS total
                        """)
                .bind(deviceIds).to("deviceIds")
                .bind(componentIds).to("componentIds")
                .bind(faultIds).to("faultIds")
                .fetchAs(Long.class)
                .first()
                .orElse(0L);
    }

    private Long getComponentFaultTotal(List<String> componentIds, List<String> faultIds) {
        return neo4jClient.query("""
                        MATCH (d:Device)-[:OWNS]->(c:Component)-[:CAUSES]->(f:Fault)
                        WHERE c.id IN $componentIds
                          AND f.id IN $faultIds
                        OPTIONAL MATCH (f)-[:HAS_SOLUTION]->(s:Solution)
                        RETURN count(*) AS total
                        """)
                .bind(componentIds).to("componentIds")
                .bind(faultIds).to("faultIds")
                .fetchAs(Long.class)
                .first()
                .orElse(0L);
    }

    private Long getComponentOnlyTotal(List<String> componentIds) {
        return neo4jClient.query("""
                        MATCH (d:Device)-[:OWNS]->(c:Component)-[:CAUSES]->(f:Fault)
                        WHERE c.id IN $componentIds
                        OPTIONAL MATCH (f)-[:HAS_SOLUTION]->(s:Solution)
                        RETURN count(*) AS total
                        """)
                .bind(componentIds).to("componentIds")
                .fetchAs(Long.class)
                .first()
                .orElse(0L);
    }

    private Long getDeviceTotal(List<String> faultIds, List<String> deviceIds) {
        return neo4jClient.query("""
                        MATCH (d:Device)
                        WHERE d.id IN $deviceIds
                        MATCH (d)-[:OWNS]->(c:Component)-[:CAUSES]->(f:Fault)
                        WHERE f.id IN $faultIds
                        OPTIONAL MATCH (f)-[:HAS_SOLUTION]->(s:Solution)
                        RETURN count(*) AS total
                        """)
                .bind(deviceIds).to("deviceIds")
                .bind(faultIds).to("faultIds")
                .fetchAs(Long.class)
                .first()
                .orElse(0L);
    }

    private Long getGlobalTotal(List<String> faultIds) {
        return neo4jClient.query("""
                        MATCH (d:Device)-[:OWNS]->(c:Component)-[:CAUSES]->(f:Fault)
                        WHERE f.id IN $faultIds
                        OPTIONAL MATCH (f)-[:HAS_SOLUTION]->(s:Solution)
                        RETURN count(*) AS total
                        """)
                .bind(faultIds).to("faultIds")
                .fetchAs(Long.class)
                .first()
                .orElse(0L);
    }

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
