package ai.weixiu.service.impl;

import ai.weixiu.pojo.PageResult;
import ai.weixiu.pojo.vo.DeviceVO;
import ai.weixiu.pojo.vo.DiagnosisPathVO;
import ai.weixiu.pojo.vo.FaultVO;
import ai.weixiu.repository.DeviceRepository;
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

    /*
     * 根据故障描述,分页查询诊断路径
     * */
    @Override
    public PageResult<DiagnosisPathVO> findDiagnosisPaths(String keyword, String faultDescription, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(size, 5);
        int skip = safePage * safeSize;

        if (!hasText(faultDescription)) {
            return emptyResult(safePage, safeSize);
        }

        // 查询匹配的故障（embedding）
        List<FaultVO> faultByEmbedding = faultService.getFaultByEmbedding(faultDescription, 20L, 0.80);
        List<String> faultIds = faultByEmbedding.stream().map(FaultVO::getId).toList();

        Map<String, Double> faultScoreMap = faultByEmbedding.stream()
                .collect(Collectors.toMap(FaultVO::getId, FaultVO::getScore));

        if (faultIds.isEmpty()) {
            return emptyResult(safePage, safeSize);
        }

        List<String> deviceIds = List.of();
        if (hasText(keyword)) {
            List<DeviceVO> devices = deviceRepository.getDevices(keyword, 0, Integer.MAX_VALUE);
            deviceIds = devices.stream().map(DeviceVO::getId).toList();
        }

        List<DiagnosisPathVO> records;
        Long total;
        if (deviceIds.isEmpty()) {
            // 没有指定设备，或指定设备没有命中：根据故障向量结果做全局图谱证据链检索。
            records = getGlobalList(faultIds, skip, safeSize);
            total = getGlobalTotal(faultIds);
        } else {
            // 命中设备：只在这些设备拥有的部件范围内寻找可能故障原因和方案。
            records = getDeviceList(faultIds, deviceIds, skip, safeSize);
            total = getDeviceTotal(faultIds, deviceIds);
        }
        for (DiagnosisPathVO vo : records) {
            vo.setFaultScore(faultScoreMap.get(vo.getFaultId()));
            //拼接pathText
            vo.setPathText(buildPathText(vo));
        }
        log.info("此次找到的诊断路径{}", records);
        return pageResult(records, total, safePage, safeSize);
    }

    private String buildPathText(DiagnosisPathVO vo) {
        StringBuilder sb = new StringBuilder(vo.getDeviceName());
        if (vo.getComponentName() != null) {
            sb.append(" -> OWNS -> ")
                    .append(vo.getComponentName())
                    .append(" -> CAUSES -> ")
                    .append(vo.getFaultName());
        } else {
            sb.append(" HAS_FAULT ")
                    .append(vo.getFaultName());
        }
        if (vo.getSolutionTitle() != null) {
            sb.append(" -> HAS_SOLUTION ")
                    .append(vo.getSolutionTitle());
        }
        return sb.toString();
    }

    private @NonNull List<DiagnosisPathVO> getDeviceList(List<String> faultIds, List<String> deviceIds, int skip, int limit) {
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

    /*
     * 分页查询设备内诊断路径 - 总数
     * */
    private Long getDeviceTotal(List<String> faultIds, List<String> deviceIds) {
        if (faultIds.isEmpty() || deviceIds.isEmpty()) {
            return 0L;
        }
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

    /*
     * 分页查询全局诊断路径 - 总数
     * */
    private Long getGlobalTotal(List<String> faultIds) {
        if (faultIds.isEmpty()) {
            return 0L;
        }
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