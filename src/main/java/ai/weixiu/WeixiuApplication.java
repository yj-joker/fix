package ai.weixiu;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("ai.weixiu.mapper")
public class WeixiuApplication {

    public static void main(String[] args) {
        SpringApplication.run(WeixiuApplication.class, args);
    }

}
