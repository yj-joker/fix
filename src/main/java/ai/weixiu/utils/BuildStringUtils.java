package ai.weixiu.utils;


import ai.weixiu.entity.Fault;
import ai.weixiu.entity.MemoryUnresolved;
import ai.weixiu.pojo.vo.GraphRagResultVO;
import ai.weixiu.entity.MemoryPreference;
import ai.weixiu.pojo.vo.DiagnosisPathVO;
import ai.weixiu.pojo.vo.FaultVO;
import ai.weixiu.pojo.vo.MemoryPreferenceVO;
import cn.hutool.json.JSONUtil;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class BuildStringUtils {
    //构建故障的嵌入文本向量
    public String buildFaultEmbeddingText(Fault fault) {
        return """
                故障名称：%s
                故障编码：%s
                故障类别：%s
                严重程度：%s
                故障描述：%s
                """.formatted(
                fault.getName(),
                fault.getCode(),
                fault.getCategory(),
                fault.getSeverity(),
                fault.getDescription()
        );
    }

    //构建部件的嵌入文本向量
    public String buildComponentEmbeddingText(ai.weixiu.entity.Component component) {
        return """
                部件名称：%s
                部件编号：%s
                规格参数：%s
                供应商：%s
                生命周期：%s
                单价：%s
                """.formatted(
                component.getName(),
                component.getPartNumber(),
                component.getSpecification(),
                component.getSupplier(),
                component.getLifecycle(),
                component.getUnitPrice()
        );
    }

    // 构建返回给AI的图谱知识结果
    public String buildGraphContextAssembler(GraphRagResultVO result) {
        StringBuilder context = new StringBuilder();
        appendQuestion(context, result);
        appendMatchedFaults(context, result.getMatchedFaults());
        appendDiagnosisPaths(context, result.getDiagnosisPaths());
        appendRules(context);
        return context.toString();
    }

    private void appendQuestion(StringBuilder context, GraphRagResultVO result) {
        context.append("【用户问题】\n");
        context.append(nullToEmpty(result.getQuestion())).append("\n\n");

        if (hasText(result.getDeviceKeyword())) {
            context.append("【设备线索】\n");
            context.append(result.getDeviceKeyword()).append("\n\n");
        }
    }

    private void appendMatchedFaults(StringBuilder context, List<FaultVO> faults) {
        context.append("【语义匹配到的故障】\n");

        if (faults == null || faults.isEmpty()) {
            context.append("未匹配到明确故障。\n\n");
            return;
        }

        for (int i = 0; i < faults.size(); i++) {
            FaultVO fault = faults.get(i);
            context.append(i + 1).append(". ")
                    .append(nullToEmpty(fault.getName())).append("\n")
                    .append("   相似度：").append(nullToEmpty(fault.getScore())).append("\n")
                    .append("   严重程度：").append(nullToEmpty(fault.getSeverity())).append("\n")
                    .append("   描述：").append(nullToEmpty(fault.getDescription())).append("\n");
        }

        context.append("\n");
    }

    private void appendDiagnosisPaths(StringBuilder context, List<DiagnosisPathVO> paths) {
        context.append("【图谱证据链】\n");

        if (paths == null || paths.isEmpty()) {
            context.append("暂无图谱证据链。\n\n");
            return;
        }

        for (int i = 0; i < paths.size(); i++) {
            DiagnosisPathVO path = paths.get(i);

            context.append(i + 1).append(". ")
                    .append(nullToEmpty(path.getPathText())).append("\n")
                    .append("   设备：").append(nullToEmpty(path.getDeviceName())).append("\n")
                    .append("   可能相关部件：").append(nullToEmpty(path.getComponentName())).append("\n")
                    .append("   匹配故障：").append(nullToEmpty(path.getFaultName())).append("\n")
                    .append("   故障相似度：").append(nullToEmpty(path.getFaultScore())).append("\n")
                    .append("   推荐方案：").append(nullToEmpty(path.getSolutionTitle())).append("\n")
                    .append("   预计耗时：").append(nullToEmpty(path.getEstimatedTime())).append(" 分钟\n")
                    .append("   是否验证：")
                    .append(Boolean.TRUE.equals(path.getVerified()) ? "是" : "否").append("\n");
        }

        context.append("\n");
    }

    private void appendRules(StringBuilder context) {
        context.append("【回答要求】\n");
        context.append("请优先依据图谱证据链回答。\n");
        context.append("如果知识图谱证据不足，请说明需要进一步检查。\n");
        context.append("不要编造知识图谱中不存在的部件、故障或解决方案。\n");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String nullToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }



}
