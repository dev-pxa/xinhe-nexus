package com.xinhe.nexus.management;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
@ComponentScan(basePackages = "com.xinhe.nexus")
@MapperScan(basePackages = "com.xinhe.nexus.common.mapper")
public class XinheManagementApplication {
    public static void main(String[] args) {
        SpringApplication.run(XinheManagementApplication.class, args);
    }
}

