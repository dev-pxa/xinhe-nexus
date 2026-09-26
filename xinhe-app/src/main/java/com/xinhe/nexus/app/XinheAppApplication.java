package com.xinhe.nexus.app;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = "com.xinhe.nexus")
@MapperScan(basePackages = "com.xinhe.nexus.common.mapper")
public class XinheAppApplication {
    public static void main(String[] args) {
        SpringApplication.run(XinheAppApplication.class, args);
    }
}

