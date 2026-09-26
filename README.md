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

