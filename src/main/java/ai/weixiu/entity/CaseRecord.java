package ai.weixiu.entity;
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
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Node("CaseRecord")//案例节点
public class CaseRecord {
    @Id
    @GeneratedValue(UUIDStringGenerator.class)
    private String id;

    @Property("case_number")//案例编号（如 C001）
    private String caseNumber;

    @Property("title")//案例标题
    private String title;

    @Property("summary")//案例摘要
    private String summary;

    @Property("diagnosis")//诊断过程
    private String diagnosis;

    @Property("resolution")//解决过程
    private String resolution;

    @Property("result")//处理结果（成功/部分成功/失败）
    private String result;

    @Property("downtime")//停机时长（分钟）
    private Integer downtime;

    @Property("experience_summary")//经验总结
    private String experienceSummary;

    @Property("cost")//维修成本
    private Double cost;

    @Property("recorded_at")//记录时间
    private LocalDateTime recordedAt;

    @Property("recorder")//记录人
    private String recorder;

    @Property("reviewed_by")//审核人
    private String reviewedBy;

    @Property("tags")//标签（如 液压,电机,紧急）
    private String tags;

    //  关系 记录了哪些故障（多对多，案例 --> 故障）
    // 案例 --[RECORDED]--> 故障
    @Relationship(type = "RECORDED", direction = Relationship.Direction.OUTGOING)
    @Builder.Default
    private Set<Fault> recordedFaults = new HashSet<>();
}
