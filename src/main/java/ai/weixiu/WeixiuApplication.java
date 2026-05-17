package ai.weixiu;

import ai.weixiu.entity.BaiDuConfigurationProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@MapperScan("ai.weixiu.mapper")
@EnableConfigurationProperties(BaiDuConfigurationProperties.class)
public class WeixiuApplication {

    public static void main(String[] args) {
        SpringApplication.run(WeixiuApplication.class, args);
    }

}
