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

通过用户管理新建普通用户或公司管理员时，手机号必填，初始密码由后端统一设置为 `xinhe123.`，且用户首次登录后需要修改密码。

登录用户可以维护自己的姓名、手机号和邮箱。修改密码时必须校验现有密码并重复输入新密码；新密码长度为 6 至 72 位，只能使用 ASCII 大写字母、小写字母、数字和标点符号，且至少包含其中三类，不允许空格或其他类型字符。

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

## 服务器与 GitHub Actions 部署

与 `training` 共用服务器时，后端端口固定为管理 API `18081`、App API `18083`、
MySQL `13306`、Redis `16379`，均不会与培训系统的 `8080`、`8082`、`3306`、`6379`
（或其 pgvector `5432`）冲突。

本机通过 SSH 发布：

```bash
chmod +x deploy/scripts/deploy-ssh.sh
deploy/scripts/deploy-ssh.sh
```

`master` 分支推送会触发 GitHub Actions。配置本项目专用 Secrets：
`NEXUS_SSH_HOST`、`NEXUS_SSH_USER`、`NEXUS_SSH_PORT`、`NEXUS_SSH_PRIVATE_KEY`、
`NEXUS_MYSQL_ROOT_PASSWORD`、`NEXUS_REDIS_PASSWORD`；可选的 Repository Variable
`NEXUS_BACKEND_DEPLOY_PATH` 默认是 `/opt/xinhe-nexus`。

## 域名 API 入口

管理端与 App API 不直接暴露为单独域名，而是由前端入口
`http://nexus.glowlink.ren` 反向代理：管理端使用 `/api/...`，App 使用
`/api/app/...`（例如 `http://nexus.glowlink.ren/api/app/system/ping`）。这样 Cookie
会保持在同一站点，且无需开放后端的 `18081`、`18083` 给浏览器。
