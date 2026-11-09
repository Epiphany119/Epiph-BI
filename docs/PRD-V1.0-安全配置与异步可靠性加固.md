# Epiph-BI 安全配置与异步可靠性加固 PRD

- **版本**：V1.0
- **状态**：待人工验收
- **范围**：仓库数据与配置管理、后端凭据配置、CORS 来源限制、异步分析任务恢复
- **目标模块**：`bi-web`、`bi-infra`、`bi-module-analysis-biz`、`bi-module-chart-biz`

## 1. 背景与问题

当前仓库将用于手动验证的 Excel 工作簿纳入 Git 跟踪；生产和测试配置文件也被跟踪，其中包含本机服务凭据。CORS 同时允许凭据和任意来源，扩大了浏览器跨域请求的风险面。此外，异步分析任务先落库为 `WAITING`，之后才提交线程池或发送 RabbitMQ 消息；线程池拒绝任务或消息投递失败时，记录可能一直停留在 `WAITING`，没有自动恢复路径。

本次优化将个人数据和环境凭据留在本机，将安全的配置模板留在仓库，限制浏览器来源，并让可恢复的 `WAITING` 分析任务在应用运行期间重新进入统一执行流程。

## 2. 目标与验收标准

1. **优先项 1：本地数据不入库。** 本地测试表及个人环境配置文件继续留在开发机，不再由 Git 跟踪；仓库提供无真实凭据的配置示例。
2. **优先项 2：配置凭据外置。** Git 跟踪的共享配置不包含数据库、Redis、Elasticsearch、RabbitMQ、微信或对象存储的真实凭据及弱口令默认值。
3. **优先项 3：收紧 CORS。** 只允许配置中明确列出的前端来源；启用 Cookie 时不得使用 `*` 或通配来源模式。
4. **补充项：恢复卡在 `WAITING` 的异步任务。** 任务若在提交执行器或消息队列前后留在 `WAITING`，应用恢复后能由定时扫描重新投递到统一执行服务；任务状态原子领取，最多只有一个执行者实际调用 AI。
5. 手动重试、任务归属和已有状态流转保持有效；失败任务仍通过既有失败重试入口处理。
6. 完成后不执行 GitHub 推送。开发者可按本文手动验收步骤验证；本次编码不替用户执行带真实凭据的联调。

## 3. 用户与边界

### 3.1 本地数据及配置

- `bi-web/src/main/resources/测试表/` 内的测试工作簿属于本地验证资料，不进入版本控制。
- `application-local.yml`、`application-prod.yml`、`application-test.yml` 属于环境私有配置，保留在本地，不由 Git 跟踪。
- 仓库只跟踪 `application.yml` 中无秘密的通用设置，以及 `.example.yml` 示例。示例中的凭据只使用 `${ENV_VAR}` 占位符或明显的示例值。
- `.gitignore` 负责阻止未来误跟踪；从索引移除文件不改动仍存在的本地文件。
- 已经进入 Git 历史的文件不会因本次忽略规则自动从历史中消失。若需要从 GitHub 历史彻底清除，需另行做历史重写并轮换曾公开的凭据；本次不推送、不改写历史。

### 3.2 配置与密钥

- 数据库、Redis、Elasticsearch、RabbitMQ、微信和对象存储的密码/密钥从环境变量或本机私有 profile 读取。
- 通用配置不得回退到 `123456`、`guest` 等可被误用于部署的凭据。
- 本机的 `application-local.yml` 仅由 `local` profile 自动加载，可提供本地服务参数和 AI Key；不得在共享配置中全局 import，文件保持在开发机，禁止加入提交。
- 示例配置说明必填项和变量名，不提供可用的真实账号或密钥。共享配置不留 `123456`、`guest` 或 `xxx` 作为可用的凭据默认值。

### 3.3 CORS

- 允许来源通过 `APP_CORS_ALLOWED_ORIGINS` 配置，使用逗号分隔的精确 Origin，例如 `http://localhost:8000`。
- 本地默认值只指向本地前端；部署环境必须配置自己的 HTTPS 前端来源。
- 允许携带 Cookie 时拒绝 `*` 以及 `allowedOriginPatterns("*")`。
- HTTP 方法和请求头继续按现有业务需求放行；不在本需求中改动身份认证机制。

### 3.4 异步任务恢复

- `WAITING` 表示任务已持久化、尚未被执行者原子领取；任务可由线程池、RabbitMQ 或恢复扫描触发。
- 恢复扫描按固定间隔运行，每轮最多检查 20 个未删除的 `WAITING` 任务，并复用 `AnalysisTaskApplicationService.executeExistingTask`。
- `tryStartAnalysis` 的 `WAITING -> RUNNING` 条件更新是唯一执行权；扫描器、线程池和 RabbitMQ 同时看到同一任务时，只能有一个成功领取并调用 AI。
- 线程池拒绝任务或 RabbitMQ 投递异常时，已落库任务保留 `WAITING`，由恢复扫描补偿；异常需要记录 `chartId` 和可诊断日志，不记录密钥。
- `RUNNING` 任务不由本版自动回收或重跑，避免长时间 AI 调用被误判后产生重复费用。崩溃后遗留的 `RUNNING` 租约/超时恢复属于后续需求。
- 扫描器不改变用户归属、删除权限和失败任务手动重试规则。

## 4. 技术设计

### 4.1 仓库与文件策略

- 在仓库根 `.gitignore` 忽略本地测试表目录和上述私有环境配置文件。
- 使用 `git rm --cached` 从索引移除已跟踪的本地资料；不要删除工作区中的文件。
- 在 `bi-web/src/main/resources/` 增加 `application-prod.example.yml`、`application-test.example.yml`，用环境变量占位并附说明。
- 将共享 `application.yml` 中凭据改为环境变量占位形式；`application-local.yml` 仅随 `local` profile 自动加载，切换到 `prod`/`test` 时不导入本机配置。微信、对象存储及 RabbitMQ 的共享项不留弱口令或 `xxx` 占位默认值。

### 4.2 CORS 配置

- `bi-infra` 的 `CorsConfig` 读取 `app.cors.allowed-origins`，按逗号拆分并清理空白。
- 使用精确 `allowedOrigins`，不调用通配 Origin Pattern。
- 默认只允许本机前端 `http://localhost:8000`；部署示例提供显式配置位置。

### 4.3 WAITING 恢复实现

- 在 `chart-biz` 的受控 `ChartApplicationService` 增加有限数量的待执行任务查询 API。
- Mapper 仅查询未删除、状态为 `WAITING`/历史兼容状态 `WAIT` 的任务 ID，按创建时间和 ID 稳定排序，并限制返回数量。
- 在 `analysis-biz` 增加受 Spring 调度管理的恢复组件，默认每 30 秒扫描一轮、批量上限 20；每个任务独立捕获异常，避免单条异常中断整轮扫描。
- 扫描结果统一交给现有 `executeExistingTask`，不复制 AI 编排、解析、持久化逻辑。
- `/chart/gen/async` 遇到执行器拒绝时保留 `WAITING` 并记录补偿日志；`/chart/gen/async/mq` 遇到投递异常时同样保留记录，交给恢复扫描补偿。接口仍返回已创建的 `chartId`，前端可通过现有任务查询接口轮询状态。

## 5. 不在本次范围

- 推送 GitHub、删除或重写远端仓库历史。
- 自动轮换已暴露凭据；实际凭据的更换由仓库所有者在相关服务完成。
- `RUNNING` 任务租约、超时判断、分布式锁和自动重跑。
- 新增任务状态查询接口或前端页面；继续使用现有任务查询能力。
- 更改 AI 模型、Prompt、表格分析逻辑、统计口径或图表规则。

## 6. 手动验收步骤

1. 确认测试表和私有 profile 文件仍在本机；运行 `git status --short` 时，文件不作为新增/修改文件出现。
2. 运行 `git check-ignore -v` 检查测试表目录与私有 profile，确认命中仓库根忽略规则。
3. 检查仓库中的配置示例和共享配置，确认只含环境变量名/示例值，不含实际密码或 API Key。
4. 将 `APP_CORS_ALLOWED_ORIGINS` 设置为本机前端 Origin 后发起带 Cookie 的跨域请求；该来源成功，未配置来源被浏览器拦截。
5. 创建异步任务后，在任务执行前短暂阻止投递或制造线程池拒绝；确认记录保持 `WAITING`，恢复条件后扫描器能使其进入 `RUNNING` 并最终到 `SUCCEEDED` 或 `FAILED`。
6. 让线程池、RabbitMQ 与恢复扫描同时触发同一 `chartId`；检查只有一次 AI 调用，数据库状态只按 `WAITING -> RUNNING -> SUCCEEDED/FAILED` 迁移。
7. 确认失败任务仍可由所属用户使用既有重试入口重试，其他用户不能重试。
8. 不执行 `git push`；PRD 与代码先留在本地供审阅。
