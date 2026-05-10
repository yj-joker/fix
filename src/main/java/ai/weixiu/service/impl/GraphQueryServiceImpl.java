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
        int skip = page * size;
        // 查询匹配的设备
        List<DeviceVO> devices = deviceRepository.getDevices(keyword, 0, Integer.MAX_VALUE);
        List<String> deviceIds = devices.stream().map(DeviceVO::getId).toList();
        // 查询匹配的故障（embedding）
        List<FaultVO> faultByEmbedding = faultService.getFaultByEmbedding(faultDescription, 20L, 0.80);
        List<String> faultIds = faultByEmbedding.stream().map(FaultVO::getId).toList();
        if (faultIds.isEmpty() || deviceIds.isEmpty()) {
            PageResult<DiagnosisPathVO> result = new PageResult<>();
            result.setRecords(List.of());
            result.setTotal(0L);
            result.setPage(page);
            result.setSize(size);
            return result;
        }
        // 查数据
        List<DiagnosisPathVO> records = getList(faultIds, deviceIds, skip, size);
        // 查总数
        Long total = getTotal(faultIds, keyword);

        PageResult<DiagnosisPathVO> result = new PageResult<>();
        result.setRecords(records);
        result.setTotal(total);
        result.setPage(page);
        result.setSize(size);
        return result;
    }

    private @NonNull List<DiagnosisPathVO> getList(List<String> faultIds, List<String> deviceIds, int skip, int limit) {
        return neo4jClient.query("""
                        MATCH (d:Device)-[:HAS_FAULT]->(f:Fault)
                        WHERE d.id IN $deviceIds AND f.id IN $faultIds
                        OPTIONAL MATCH (d)-[:OWNS]->(c:Component)-[:CAUSES]->(f)
                        OPTIONAL MATCH (f)-[:HAS_SOLUTION]->(s:Solution)
                        RETURN d.id AS deviceId, d.name AS deviceName,
                               c.id AS componentId, c.name AS componentName,
                               f.id AS faultId, f.name AS faultName, f.severity AS faultSeverity,
                               s.id AS solutionId, s.title AS solutionTitle,
                               s.estimated_time AS estimatedTime, s.verified AS verified
                        ORDER BY d.name, s.verified DESC, s.estimated_time ASC
                        SKIP $skip
                        LIMIT $limit
                        """)
                .bind(deviceIds).to("deviceIds")
                .bind(faultIds).to("faultIds")
                .bind(skip).to("skip")
                .bind(limit).to("limit")
                .fetchAs(DiagnosisPathVO.class)
                .mappedBy((ctx, record) -> {
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
                })
                .all()
                .stream()
                .toList();
    }

    /*
     * 分页查询诊断路径 - 总数
     * */
    private Long getTotal(List<String> faultIds, String keyword) {
        List<DeviceVO> devices = deviceRepository.getDevices(keyword, 0, Integer.MAX_VALUE);
        List<String> deviceIds = devices.stream().map(DeviceVO::getId).toList();
        if (deviceIds.isEmpty()) {
            return 0L;
        }
        return neo4jClient.query("""
                        MATCH (d:Device)-[:OWNS]->(c:Component)-[:CAUSES]->(f:Fault)-[:HAS_SOLUTION]->(s:Solution)
                        WHERE d.id IN $deviceIds AND f.id IN $faultIds
                        RETURN count(s) AS total
                        """)
                .bind(deviceIds).to("deviceIds")
                .bind(faultIds).to("faultIds")
                .fetchAs(Long.class)
                .first()
                .orElse(0L);
    }

}
