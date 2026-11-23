# Epiph-BI 后端多模块架构说明

> 当前项目由 Epiphany119 基于 [程序员鱼皮](https://github.com/liyupi) 的 Spring Boot 初始模板进行多模块架构重构与 AI 工程化二次开发；保留原作者与上游来源声明。

## 模块划分与职责边界

依赖方向遵循单向分层约束（详见 [../docs/模块边界-V1.0.md](../docs/模块边界-V1.0.md)）：`bi-web` 负责装配，业务模块之间通过受控 ApplicationService 接口协作，不跨模块直连 Mapper。

| Maven 模块 | 核心职责 | 关键实现 |
| --- | --- | --- |
| `bi-common` | 跨模块通用响应、异常、错误码与基础分页 DTO | `BaseResponse`、`BusinessException`、`ErrorCode` |
| `bi-infra` | MyBatis-Plus、Redisson 分布式限流、CORS 精确源校验、有界线程池、Excel 解析 | `RedisLimiterManager`、`CorsConfig`、`ThreadPoolExecutorConfig` |
| `bi-ai-runtime` | 独立大模型运行时：协议封装、模型白名单、指数退避重试、多模型自动 Fallback、Token 成本估算 | `ZhipuAiChatClient`、`ResilientAiChatClient`、`AiCostCalculator` |
| `bi-module-user-biz` | 用户注册、登录、会话与权限拦截 | `UserController`、`UserServiceImpl`、`AuthInterceptor` |
| `bi-module-file-biz` | 可复用数据集资产管理：表格解析、列画像提取、前 20 行预览与归属校验 | `DatasetController`、`DatasetApplicationServiceImpl`、`BiDataset` |
| `bi-module-chart-biz` | 图表任务 CRUD、CAS 租约状态机、超时回收、AI 用量账单与 FinOps 汇总查询 | `ChartManagementController`、`ChartApplicationServiceImpl`、`AiUsageLogMapper` |
| `bi-module-analysis-biz` | Plan-Compute-Explain 分析编排、本地过滤与全量聚合、ECharts 构造、多轮下钻、MQ 消费与超时任务恢复 | `AnalysisTaskApplicationService`、`SpreadsheetAnalysisService`、`WaitingAnalysisTaskRecoveryJob` |
| `bi-web` | Spring Boot 启动入口与全局配置装配 | `MainApplication`、`application.yml` |

## 关键配置项

在 `modules/bi-web/src/main/resources/application.yml` 中已外置敏感凭据，支持通过环境变量或 `local` profile 私有文件配置：

- `APP_CORS_ALLOWED_ORIGINS`：允许携带 Cookie 的精确前端 Origin（默认 `http://localhost:8000`）。
- `ANALYSIS_TASK_RECOVERY_INTERVAL_MS`：异步任务超时回收与 `WAITING` 补偿扫描间隔（默认 `30000` ms）。
- `ANALYSIS_TASK_LEASE_SECONDS`：`RUNNING` 任务租约时长（默认 `120` 秒）。
- `bi.ai.default-model` / `bi.ai.allowed-models` / `bi.ai.fallback-models`：主模型、白名单与自动降级备用模型链。
- `bi.ai.retry.*` / `bi.ai.pricing.*`：指数退避参数与各模型百万 Token 单价配置。

## 编译与测试

```bash
# 全量单元测试（无需外部 MySQL / Redis / AI Key）
mvn test

# 打包可执行 Jar
mvn -DskipTests package

# 运行 24 场景架构对比评测并更新评测报告
mvn -pl modules/bi-module-analysis-biz test -Dtest=BiArchitectureBenchmarkTest -DwriteEvalReport=true
```
