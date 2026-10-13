package com.riskstrategy;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 风控策略配置平台 - 后端服务入口 (Java 21 + Spring Boot)
 */
@SpringBootApplication
@MapperScan("com.riskstrategy.mapper")
public class RiskStrategyApplication {
    public static void main(String[] args) {
        SpringApplication.run(RiskStrategyApplication.class, args);
    }
}
