package com.xinhe.nexus.management.config;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 显式执行管理系统数据库迁移。
 *
 * <p>Spring Boot 4 将部分自动配置拆分为独立模块，因此这里直接注册 Flyway，确保在任何
 * {@link org.springframework.boot.ApplicationRunner} 执行前完成表结构升级和基础数据表创建。</p>
 */
@Configuration
public class DatabaseMigrationConfig {

    @Bean(initMethod = "migrate")
    public Flyway managementFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("0"))
                .load();
    }
}
