# Epiph-BI

> Copyright © 2026 Epiphany119. 本项目基于上游模板进行二次开发；上游作者与来源标注保留在对应文件中。

基于 React + Spring Boot + RabbitMQ + AIGC 的智能数据分析平台。

## 项目结构

- `yubi-backend-master`：Spring Boot 后端，已调整为 JDK 17 编译。
- `yubi-frontend-master`：React 18 + Umi + Ant Design Pro 前端。

## IDEA 启动后端

1. 用 IntelliJ IDEA 打开 `yubi-backend-master/pom.xml`，选择 Maven 项目导入。
2. Project SDK 和 Maven Runner 都选择 JDK 17。
3. 创建数据库 `yubi`，执行 `yubi-backend-master/sql/create_table.sql`。
4. 启动 MySQL、Redis、RabbitMQ。
5. 通过环境变量配置密钥；本地开发至少需要 `MYSQL_PASSWORD`、`YUAPI_ACCESS_KEY` 和 `YUAPI_SECRET_KEY`。
6. 运行 `com.yupi.springbootinit.MainApplication`，后端地址为 `http://localhost:8080/api`。

也可以在后端目录执行：

```bash
./mvnw -DskipTests package
java -jar target/yubi-backend-0.0.1-SNAPSHOT.jar
```

## 前端启动

```bash
cd yubi-frontend-master
pnpm install
pnpm start:dev
```

前端默认访问 `http://localhost:8000`。

## 适合简历描述的真实亮点

- 使用 EasyExcel 将用户上传的 XLSX 数据转换为紧凑 CSV，降低 AI 请求上下文长度。
- 通过 Prompt 约束模型输出 ECharts JSON 和结构化分析结论。
- 使用 RabbitMQ 将 AI 图表生成从同步请求改造为异步任务，降低接口阻塞。
- 使用 Redisson RateLimiter 对 AI 生成接口进行用户级分布式限流。
- 使用 Spring Session + Redis 支持分布式登录状态。

请只描述自己实际理解、运行和二次开发过的功能，并在面试中能够说明取舍、异常处理和测试方式。
