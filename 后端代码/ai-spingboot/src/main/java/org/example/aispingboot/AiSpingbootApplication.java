package org.example.aispingboot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 心理健康 AI 助手后端启动类。
 * 负责启动 Spring Boot 应用，并自动扫描当前包及子包下的组件。
 */
@SpringBootApplication
public class AiSpingbootApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiSpingbootApplication.class, args);
    }

}
