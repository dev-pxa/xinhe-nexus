# 芯河智能后端

技术框架和模块划分与 `training` 后端一致：Spring Boot 4、Java 21、Maven 多模块、MyBatis、MySQL、Redis。

```text
xinhe-common       公共实体、Mapper、服务和基础设施
xinhe-management   管理系统后端，端口 18081
xinhe-app          移动端后端，端口 18083
```

## 本地启动管理端

先启动 MySQL 与 Redis，然后执行：

```bash
mvn spring-boot:run -pl xinhe-management -am
```

管理端登录接口使用 Redis 保存会话，并通过 `HttpOnly` Cookie 下发登录凭证。账号保存在 `sys_user`，密码只保存 BCrypt 哈希。默认初始化两个账号：

```text
平台管理员：root / root
总公司管理员：admin / admin
```

平台管理员创建公司后，系统会在同一事务中自动生成公司编号、公司根组织和公司管理员。公司编号格式为 `COMP` 加至少 8 位公司ID（例如 `COMP00000002`），创建后不可修改。新公司管理员的账号、初始密码均为该公司编号 `company_code`。生产环境首次登录后必须及时修改默认密码。

认证接口：

```text
POST /api/auth/login    登录并设置 Cookie
GET  /api/auth/current  获取当前登录用户
POST /api/auth/logout   注销并清除 Cookie
```

## 数据库迁移与测试

管理服务使用 Flyway 自动执行版本化迁移，已有 Docker 数据卷无需删除。旧版 `sys_user` 会备份为 `sys_user_legacy`。正式结构位于 `xinhe-management/src/main/resources/db/migration/`，所有业务表和字段均带中文注释。

后端镜像构建会执行全部 Maven 自动化测试，任何测试失败都会中止构建：

```bash
docker compose build management
```

服务启动后可执行认证和数据库接口冒烟测试：

```bash
bash deploy/scripts/management-smoke-test.sh
bash deploy/scripts/management-company-e2e-test.sh
```

第二个脚本会真实创建测试公司，验证根组织、同编号公司管理员及登录会话，然后自动清理本次测试数据。

## 后端独立 Docker 部署

```bash
cp .env.example .env
# 修改 .env 中密码
./deploy/scripts/deploy.sh
```

端口：管理 API `18081`、App API `18083`、MySQL `13306`、Redis `16379`。这些宿主机端口均与 `training` 不同。

健康检查：

```bash
curl http://localhost:18081/actuator/health
curl http://localhost:18083/actuator/health
```
