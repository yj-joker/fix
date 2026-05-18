package ai.weixiu;

import ai.weixiu.config.BaiDuConfigurationProperties;
import ai.weixiu.config.MinioProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@MapperScan("ai.weixiu.mapper")
@EnableConfigurationProperties({BaiDuConfigurationProperties.class, MinioProperties.class})
public class WeixiuApplication {

    public static void main(String[] args) {
        SpringApplication.run(WeixiuApplication.class, args);
    }

}
