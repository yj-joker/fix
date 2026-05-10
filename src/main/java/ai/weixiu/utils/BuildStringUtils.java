package ai.weixiu.utils;


import ai.weixiu.entity.Fault;

public class BuildStringUtils {
    public static  String buildFaultEmbeddingText(Fault fault) {
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

}
