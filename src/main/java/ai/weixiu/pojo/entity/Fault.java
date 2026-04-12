package ai.weixiu.pojo.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.*;
import org.springframework.data.neo4j.core.support.UUIDStringGenerator;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Node("Fault")//故障节点
public class Fault {
    @Id
    @GeneratedValue(UUIDStringGenerator.class)
    private String id;

    @Property("code")//故障编码（如 F001）
    private String code;

    @Property("name")//故障名称
    private String name;

    @Property("description")//故障描述
    private String description;

    @Property("severity") //严重程度（轻微/一般/严重/致命）
    private String severity;

    @Property("category")//故障类别（机械/电气/软件/其他）
    private String category;

    @Property("occurrence_time")//发生时间
    private LocalDateTime occurrenceTime;

    @Property("reported_by")//报告人
    private String reportedBy;

    // 关系 涉及哪些部件（多对多，部件 --> 故障，故障端是 INCOMING）
    @Relationship(type = "CAUSES", direction = Relationship.Direction.INCOMING)
    @Builder.Default
    private Set<Component> involvedComponents = new HashSet<>();

    // 关系 有哪些解决方案（故障 --> 解决方案）
    @Relationship(type = "HAS_SOLUTION", direction = Relationship.Direction.OUTGOING)
    @Builder.Default
    private Set<Solution> solutions = new HashSet<>();

    // 关系 被哪些案例记录（多对多，故障 --> 案例，故障端是 INCOMING）
    @Relationship(type = "RECORDED_BY", direction = Relationship.Direction.INCOMING)
    @Builder.Default
    private Set<CaseRecord> caseRecords = new HashSet<>();

    // 关系 发生在哪个设备上（故障 --> 设备，故障端是 OUTGOING）
    @Relationship(type = "OCCURS_ON", direction = Relationship.Direction.OUTGOING)
    private Device device;
}
