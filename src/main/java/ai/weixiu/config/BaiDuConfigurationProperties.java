package ai.weixiu.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "baidu.speech")
@Data
public class BaiDuConfigurationProperties {
    private String apiKey;
    private String secretKey;
    private String cuid;
    private Integer devPid;
    private Integer rate;
    private Integer maxUploadMb;
}
