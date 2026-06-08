package ai.weixiu.service.impl;

import ai.weixiu.entity.ManualDevice;
import ai.weixiu.mapper.ManualDeviceMapper;
import ai.weixiu.pojo.dto.GraphIngestDTO;
import ai.weixiu.service.GraphIngestService;
import ai.weixiu.utils.EmbeddingUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;

@Service
@AllArgsConstructor
@Slf4j
public class GraphIngestServiceImpl implements GraphIngestService {

    private final Neo4jClient neo4jClient;
    private final EmbeddingUtils embeddingUtils;
    private final ManualDeviceMapper manualDeviceMapper;

    @Override
    @Transactional
    public int ingestFromManual(GraphIngestDTO dto) {
        Long manualId = dto.getManualId();
        if (manualId == null) throw new IllegalArgumentException("manualId 不能为空");
        int touched = 0;

        Map<String, String> compIdByTemp = new HashMap<>();
        for (GraphIngestDTO.ComponentItem c : safe(dto.getComponents())) {
            if (!StringUtils.hasText(c.getName())) continue;
            List<Double> vec = embeddingUtils.getEmbedding(
                    c.getName() + " " + Optional.ofNullable(c.getSpecification()).orElse(""));
            String id = neo4jClient.query("""
                    MERGE (c:Component {name: $name})
                    ON CREATE SET c.id = randomUUID(), c.created_at = datetime()
                    SET c.specification = coalesce($spec, c.specification),
                        c.embedding = $vec,
                        c.source = 'manual_auto',
                        c.source_manual_ids = CASE WHEN $manualId IN coalesce(c.source_manual_ids, [])
                                                   THEN c.source_manual_ids
                                                   ELSE coalesce(c.source_manual_ids, []) + $manualId END
                    RETURN c.id AS id
                    """)
                    .bind(c.getName()).to("name")
                    .bind(c.getSpecification()).to("spec")
                    .bind(vec).to("vec")
                    .bind(manualId).to("manualId")
                    .fetchAs(String.class).one().orElseThrow();
            compIdByTemp.put(c.getTempId(), id);
            touched++;
        }

        List<String> deviceIds = new ArrayList<>();
        for (String dn : safe(dto.getDeviceNames())) {
            if (!StringUtils.hasText(dn)) continue;
            String did = neo4jClient.query("""
                    MERGE (d:Device {name: $name})
                    ON CREATE SET d.id = randomUUID(), d.created_at = datetime()
                    RETURN d.id AS id
                    """).bind(dn).to("name").fetchAs(String.class).one().orElseThrow();
            deviceIds.add(did);
        }
        for (String did : deviceIds) {
            for (String compId : compIdByTemp.values()) {
                neo4jClient.query("MATCH (d:Device{id:$d}),(c:Component{id:$c}) MERGE (d)-[:OWNS]->(c)")
                        .bind(did).to("d").bind(compId).to("c").run();
            }
        }

        Map<String, String> faultIdByTemp = new HashMap<>();
        for (GraphIngestDTO.FaultItem f : safe(dto.getFaults())) {
            if (!StringUtils.hasText(f.getName())) continue;
            List<Double> vec = embeddingUtils.getEmbedding(
                    f.getName() + " " + Optional.ofNullable(f.getDescription()).orElse(""));
            String fid = neo4jClient.query("""
                    MERGE (f:Fault {name: $name})
                    ON CREATE SET f.id = randomUUID(), f.created_at = datetime()
                    SET f.description = coalesce($desc, f.description),
                        f.severity = coalesce($sev, f.severity),
                        f.category = coalesce($cat, f.category),
                        f.embedding = $vec,
                        f.source = 'manual_auto',
                        f.source_manual_ids = CASE WHEN $manualId IN coalesce(f.source_manual_ids, [])
                                                   THEN f.source_manual_ids
                                                   ELSE coalesce(f.source_manual_ids, []) + $manualId END
                    RETURN f.id AS id
                    """)
                    .bind(f.getName()).to("name")
                    .bind(f.getDescription()).to("desc")
                    .bind(f.getSeverity()).to("sev")
                    .bind(f.getCategory()).to("cat")
                    .bind(vec).to("vec")
                    .bind(manualId).to("manualId")
                    .fetchAs(String.class).one().orElseThrow();
            faultIdByTemp.put(f.getTempId(), fid);
            touched++;

            String compId = compIdByTemp.get(f.getRelatedComponentTempId());
            if (compId != null) {
                neo4jClient.query("MATCH (c:Component{id:$c}),(f:Fault{id:$f}) MERGE (c)-[:CAUSES]->(f)")
                        .bind(compId).to("c").bind(fid).to("f").run();
            }
            for (String did : deviceIds) {
                neo4jClient.query("MATCH (d:Device{id:$d}),(f:Fault{id:$f}) MERGE (d)-[:HAS_FAULT]->(f)")
                        .bind(did).to("d").bind(fid).to("f").run();
            }
        }

        for (GraphIngestDTO.SolutionItem s : safe(dto.getSolutions())) {
            if (!StringUtils.hasText(s.getTitle())) continue;
            String sid = neo4jClient.query("""
                    MERGE (s:Solution {title: $title})
                    ON CREATE SET s.id = randomUUID(), s.created_at = datetime(), s.verified = false
                    SET s.description = coalesce($summary, s.description),
                        s.tools_required = coalesce($tools, s.tools_required),
                        s.difficulty = coalesce($diff, s.difficulty),
                        s.estimated_time = coalesce($time, s.estimated_time),
                        s.source = 'manual_auto',
                        s.source_manual_ids = CASE WHEN $manualId IN coalesce(s.source_manual_ids, [])
                                                   THEN s.source_manual_ids
                                                   ELSE coalesce(s.source_manual_ids, []) + $manualId END
                    RETURN s.id AS id
                    """)
                    .bind(s.getTitle()).to("title")
                    .bind(s.getSummary()).to("summary")
                    .bind(s.getToolsRequired()).to("tools")
                    .bind(s.getDifficulty()).to("diff")
                    .bind(s.getEstimatedTime()).to("time")
                    .bind(manualId).to("manualId")
                    .fetchAs(String.class).one().orElseThrow();
            touched++;

            String fid = faultIdByTemp.get(s.getRelatedFaultTempId());
            if (fid != null) {
                neo4jClient.query("MATCH (f:Fault{id:$f}),(s:Solution{id:$s}) MERGE (f)-[:HAS_SOLUTION]->(s)")
                        .bind(fid).to("f").bind(sid).to("s").run();
            }
        }

        // 抽取入库完成后：若该手册在上传时已被管理员关联了设备（manual_device 已有行），
        // 此时部件刚刚 MERGE 进图谱，补建 Device-[:OWNS]->Component 边。
        // 解决"先上传关联设备、后异步抽取"的时序问题——上传时连不上的边在这里兜底补齐。
        try {
            List<ManualDevice> linkedDevices = manualDeviceMapper.selectList(
                    Wrappers.<ManualDevice>lambdaQuery().eq(ManualDevice::getManualId, manualId));
            for (ManualDevice md : linkedDevices) {
                int linked = linkManualComponentsToDevice(manualId, md.getDeviceId());
                if (linked > 0) {
                    log.info("[手册图谱入库] 抽取后补边 manualId={}, deviceId={}, 部件数={}",
                            manualId, md.getDeviceId(), linked);
                }
            }
        } catch (Exception e) {
            // 补边失败不影响实体入库主流程
            log.warn("[手册图谱入库] 抽取后补 Device-OWNS 边失败 manualId={}: {}", manualId, e.getMessage());
        }

        log.info("[手册图谱入库] manualId={} 处理节点={} (comp={},fault={},sol={})",
                manualId, touched, compIdByTemp.size(), faultIdByTemp.size(), safe(dto.getSolutions()).size());
        return touched;
    }

    @Override
    @Transactional
    public int linkManualComponentsToDevice(Long manualId, String deviceId) {
        if (manualId == null || deviceId == null) return 0;
        return neo4jClient.query("""
                MATCH (c:Component) WHERE $manualId IN coalesce(c.source_manual_ids, [])
                MATCH (d:Device {id: $deviceId})
                MERGE (d)-[:OWNS]->(c)
                RETURN count(c) AS n
                """)
                .bind(manualId).to("manualId")
                .bind(deviceId).to("deviceId")
                .fetchAs(Long.class).one().orElse(0L).intValue();
    }

    @Override
    public List<Map<String, Object>> listUnverified(int limit) {
        return new ArrayList<>(neo4jClient.query("""
                MATCH (s:Solution) WHERE s.verified = false AND s.source = 'manual_auto'
                OPTIONAL MATCH (f:Fault)-[:HAS_SOLUTION]->(s)
                RETURN s.id AS id, s.title AS title, s.description AS description,
                       f.name AS faultName, s.source_manual_ids AS sourceManualIds
                LIMIT $limit
                """).bind(limit).to("limit").fetch().all());
    }

    @Override
    @Transactional
    public void approveSolution(String solutionId) {
        neo4jClient.query("MATCH (s:Solution {id:$id}) SET s.verified = true")
                .bind(solutionId).to("id").run();
    }

    @Override
    @Transactional
    public void rejectNode(String label, String nodeId) {
        if (!List.of("Component", "Fault", "Solution").contains(label))
            throw new IllegalArgumentException("不支持的label: " + label);
        neo4jClient.query("MATCH (n:" + label + " {id:$id}) WHERE n.source = 'manual_auto' DETACH DELETE n")
                .bind(nodeId).to("id").run();
    }

    private <T> List<T> safe(List<T> l) { return l == null ? List.of() : l; }
}
