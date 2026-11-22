# Epiph-BI V2.0 AI 全栈能力闭环与工程升级 PRD

- **版本**：V2.0
- **状态**：待评审（编码前基线文档）
- **定位目标**：面向 **AI 全栈开发岗**，将 Epiph-BI 从“单次表格生图工具”升级为具备**分布式任务高可用、AI 运行时弹性治理与 FinOps 成本可观测、数据集复用与多轮下钻可解释 BI、以及可复现量化评测基准**的完整 AI 工程系统。
- **涉及模块**：`bi-ai-runtime`、`bi-module-chart-biz`、`bi-module-analysis-biz`、`bi-module-file-biz`、`bi-infra`、`bi-web`、`yubi-frontend-master`

---

## 1. 背景与核心痛点

V1.0 已完成多模块拆分与“AI 规划 + 本地全量精确聚合”的防幻觉核心引擎，但面向 AI 全栈岗位面试与真实生产场景，仍存在四条关键链路未闭环：

1. **异步任务高可用未闭环**：`WaitingAnalysisTaskRecoveryJob` 仅恢复 `WAITING` 任务；若服务实例在 `RUNNING` 阶段宕机或 OOM，任务将永久僵死，且缺乏最大重试次数与租约防脑裂（Fencing）机制。
2. **AI Runtime 治理与成本可观测未闭环**：`ZhipuAiChatClient` 已解析 `retryable` 错误语义与 `inputTokens / outputTokens`，但上层尚未接入指数退避重试、限流/5xx 自动降级备用模型，也缺少 Token 账单持久化与工作台观测。
3. **产品形态停留在“一次性单图生成”**：缺少数据集复用、多轮追问下钻与可解释性展示（后端已生成的 `planReason` 与统计口径 `note` 未在前端形成产品级透明体验），`bi-module-file-biz` 尚为空壳。
4. **缺少量化对比证据与工程门面清理**：缺少“直接喂 CSV”与“两阶段本地聚合”的客观对比评测数据；仓库内仍残留 `Post*`、教学 MQ Demo 等上游模板代码，影响代码库专业度。

---

## 2. 总体升级路线与分期里程碑

为控制变更风险，升级按 **4 个可独立编译、独立单测、独立演示的 Phase** 推进，所有阶段均严格遵守 `docs/模块边界-V1.0.md` 依赖方向并保持既有 `/api/chart/**` 兼容：

| 阶段 | 核心主题 | 关键产出 | 主要模块 |
| --- | --- | --- | --- |
| **Phase 1** | 异步任务可靠性闭环 | `RUNNING` 租约超时回收、Fencing 防旧节点覆盖、最大重试次数限制 | `bi-module-chart-biz`、`bi-module-analysis-biz`、`sql/` |
| **Phase 2** | AI Runtime 弹性治理与 FinOps | 指数退避重试、多模型自动 Fallback、Token/成本持久化与工作台观测面板 | `bi-ai-runtime`、`bi-module-chart-biz`、`bi-module-analysis-biz`、前端 `Dashboard` |
| **Phase 3** | 数据集复用、多轮下钻与可解释 BI | 落地 `bi-module-file-biz`、本地过滤+多轮追问下钻、前端规划透明化卡片与下钻抽屉 | `bi-module-file-biz`、`bi-module-analysis-biz`、前端 `MyChart / Dataset` |
| **Phase 4** | 离线量化评测基准与仓库门面重构 | 20+ 用例对比评测套件（Baseline vs V2）、自动生成评测报告、清理模板死代码与重写 README | 测试评测套件、`bi-infra`、`README.md` |

---

## 3. Phase 1：分布式异步任务可靠性闭环

### 3.1 目标与约束

- **消除 `RUNNING` 僵尸任务**：每个被领取的 `RUNNING` 任务绑定租约过期时间 `leaseExpireTime` 与执行节点标识 `workerId`；超出租约仍未完成时由恢复任务自动回收。
- **防无限重跑烧 Token**：为任务引入 `attemptCount` 与 `maxAttempts`（默认 3）；超过上限后原子标记为 `FAILED`，不再自动补偿。
- **Fencing 防脑裂写入**：若节点 A 长时间卡顿导致租约超时、任务被节点 B 抢占重跑，节点 A 延迟返回的结果不得覆盖节点 B 的状态（`completeAnalysis` / `failAnalysis` 校验 `workerId`）。
- **兼容既有语义**：手动重试（`/api/chart/retry`）仍仅允许任务所属用户在 `FAILED` 状态触发，触发后重置 `attemptCount = 0` 并清空租约。

### 3.2 数据库增量设计（`sql/upgrade_v2_phase1.sql`）

在 `chart` 表新增以下列与索引：

```sql
ALTER TABLE chart
    ADD COLUMN attemptCount INT NOT NULL DEFAULT 0 COMMENT '已尝试执行次数',
    ADD COLUMN maxAttempts INT NOT NULL DEFAULT 3 COMMENT '最大自动尝试次数',
    ADD COLUMN leaseExpireTime DATETIME NULL COMMENT 'RUNNING 租约过期时间',
    ADD COLUMN workerId VARCHAR(64) NULL COMMENT '当前持有租约的执行节点标识',
    ADD INDEX idx_status_lease (status, leaseExpireTime, isDelete);
```

### 3.3 状态机与原子 SQL 改造

1. **`tryStartAnalysis(chartId, workerId, leaseSeconds)`**：
   - 条件：`id = #{chartId} AND isDelete = 0 AND UPPER(status) IN ('WAITING', 'WAIT') AND attemptCount < maxAttempts`
   - 更新：`status = 'RUNNING'`, `workerId = #{workerId}`, `leaseExpireTime = DATE_ADD(NOW(), INTERVAL #{leaseSeconds} SECOND)`, `attemptCount = attemptCount + 1`, `updateTime = CURRENT_TIMESTAMP`。
2. **`reclaimExpiredRunningTasks(limit)`（新增）**：
   - 查询 `isDelete = 0 AND UPPER(status) = 'RUNNING' AND leaseExpireTime IS NOT NULL AND leaseExpireTime < NOW()` 的任务列表（含 `id, attemptCount, maxAttempts`）。
   - 若 `attemptCount < maxAttempts`：原子执行 `UPDATE chart SET status = 'WAITING', workerId = NULL, leaseExpireTime = NULL WHERE id = #{id} AND status = 'RUNNING' AND leaseExpireTime < NOW()`，回退到待执行队列。
   - 若 `attemptCount >= maxAttempts`：原子执行 `UPDATE chart SET status = 'FAILED', workerId = NULL, leaseExpireTime = NULL, execMessage = '任务执行超时且已达最大自动重试次数，请检查数据后手动重试' WHERE id = #{id} AND status = 'RUNNING' AND leaseExpireTime < NOW()`。
3. **`completeAnalysis` / `failAnalysis` 增加 `workerId` 守卫**：
   - 条件追加 `AND workerId = #{workerId}`，成功或失败落库后清空 `leaseExpireTime` 与 `workerId`。
4. **恢复任务升级**：
   - 将 `WaitingAnalysisTaskRecoveryJob` 扩展为先回收超时 `RUNNING` 任务，再扫描 `WAITING` 任务提交有界线程池执行；扫描间隔与租约时长（默认 `lease-seconds: 120`）支持配置化。

### 3.4 Phase 1 验收标准

- 单元测试覆盖：正常领取增加 `attemptCount`、超时 `RUNNING` 自动回退 `WAITING`、达 `maxAttempts` 自动转 `FAILED`、旧 `workerId` 写入被拒绝、手动重试重置计数。
- 全量 `mvn test` 通过。

---

## 4. Phase 2：AI Runtime 弹性治理与 FinOps 成本可观测

### 4.1 目标与约束

- **不破坏底层 Provider 边界**：`ZhipuAiChatClient` 保持纯协议客户端职责，通过装饰器 `ResilientAiChatClient`（标记 `@Primary`）统一提供重试、退避、降级与耗时采集。
- **精准区分可重试错误**：仅当 `AiRuntimeException.isRetryable() == true`（HTTP 429、5xx、网络异常、超时）时重试；401/403 鉴权失败、配置错误、`MODEL_NOT_ALLOWED` 直接快速失败。
- **自动多模型 Fallback**：主模型重试耗尽后，按 `bi.ai.fallback-models` 白名单顺序切换备用模型（例如 `glm-4-plus -> glm-4-air -> glm-4-flash`）。
- **Token 与成本全链路可观测**：将每次规划（`PLANNING`）与解读（`EXPLAINING`）调用的模型名、是否降级、重试次数、输入/输出 Token、耗时和估算费用落库，并在前端工作台可视化展示。

### 4.2 配置与模型扩展（`bi-ai-runtime`）

在 `AiRuntimeProperties` 新增：

- `fallbackModels: List<String>`（必须为 `allowedModels` 子集）
- `retry.maxRetries`（默认 `2`）、`retry.initialBackoffMs`（默认 `500`）、`retry.maxBackoffMs`（默认 `4000`）、`retry.jitterRatio`（默认 `0.2`）
- `pricing: Map<String, ModelPricing>`（按模型配置 `inputPerMillionCny` / `outputPerMillionCny`）

在 `AiChatResponse` 新增可观测元数据字段：

- `attemptCount`（总尝试次数）
- `fallbackUsed`（是否触发备用模型降级）
- `latencyMs`（端到端耗时毫秒）

### 4.3 Token 账单表与统计接口（`sql/upgrade_v2_phase2.sql`）

新增 `ai_usage_log` 表记录每次模型调用明细：

```sql
CREATE TABLE IF NOT EXISTS ai_usage_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    userId BIGINT NOT NULL COMMENT '用户ID',
    chartId BIGINT NULL COMMENT '关联图表任务ID',
    stage VARCHAR(32) NOT NULL COMMENT 'PLANNING / EXPLAINING / FOLLOWUP',
    requestedModel VARCHAR(64) NOT NULL COMMENT '请求模型',
    actualModel VARCHAR(64) NOT NULL COMMENT '实际执行模型',
    fallbackUsed TINYINT NOT NULL DEFAULT 0 COMMENT '是否触发降级',
    attemptCount INT NOT NULL DEFAULT 1 COMMENT '尝试次数',
    success TINYINT NOT NULL DEFAULT 1 COMMENT '是否成功',
    errorCode VARCHAR(64) NULL COMMENT '失败错误码',
    inputTokens INT NOT NULL DEFAULT 0,
    outputTokens INT NOT NULL DEFAULT 0,
    totalTokens INT NOT NULL DEFAULT 0,
    estimatedCostCny DECIMAL(12, 6) NOT NULL DEFAULT 0.000000,
    latencyMs BIGINT NOT NULL DEFAULT 0,
    createTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_time (userId, createTime),
    INDEX idx_chart_id (chartId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 调用用量与成本日志';
```

同时在 `chart` 表冗余汇总列 `actualModel`、`totalTokens`、`estimatedCostCny`、`latencyMs`，避免列表查询产生 N+1 关联。

新增接口：`GET /api/chart/usage/summary`（返回当前用户的总调用次数、累计 Token、估算总费用、降级触发次数、平均耗时及近 7 天用量趋势）。

### 4.4 前端工作台升级（`Dashboard.tsx`）

- 在现有指标卡下方新增 **AI 成本与性能观测区**：展示累计 Token、估算费用（元）、平均分析耗时、模型降级保障次数。
- 在“我的图表”卡片/详情中展示单次分析消耗的 Token、耗时与实际模型标签（触发降级时显示提示 Tag）。

### 4.5 Phase 2 验收标准

- 单元测试覆盖：429/5xx 触发指数退避重试并在第 2 次成功、主模型持续 503 自动切换备用模型并标记 `fallbackUsed=true`、401 鉴权失败零重试直接抛出、Token 费用计算与日志落库正确。
- 工作台前端正常渲染用量汇总数据且构建无报错。

---

## 5. Phase 3：数据集复用、多轮追问下钻与可解释 BI

### 5.1 目标与约束

- **从“单次传表”升级为“数据集资产”**：落地 `bi-module-file-biz`，上传的 Excel/CSV 解析并保存为可复用数据集（含列画像 JSON、行数、列数、样例预览），用户可基于同一数据集反复发起不同视角的分析，无需重复上传。
- **支持本地过滤与多轮下钻追问**：在已有图表基础上，用户输入自然语言追问（如“只看华东区，按月份拆成折线图”或“把 Top 1 类别按渠道做成堆叠图”），系统基于同一数据集执行**确定性条件过滤 + 重新聚合**，并生成父子血缘关系（`parentChartId`）。
- **可解释 BI 透明化**：前端将后端已经产出的 `planReason`（AI 字段选择依据）、过滤条件、统计口径 `note`（总行数、Top-N 合并说明、跳过空值数）以结构化卡片呈现，消除 AI 黑盒感。

### 5.2 数据库增量设计（`sql/upgrade_v2_phase3.sql`）

1. 新增 `bi_dataset` 表（归属 `bi-module-file-biz`）：
   - 字段：`id`, `userId`, `name`, `originalFilename`, `rowCount`, `columnCount`, `schemaProfileJson`（列画像 JSON）, `csvContent`（MEDIUMTEXT，受大小上限校验控制，支持后续平滑替换为对象存储 key）, `createTime`, `updateTime`, `isDelete`。
2. `chart` 表新增关联列：
   - `datasetId BIGINT NULL COMMENT '关联数据集ID'`
   - `parentChartId BIGINT NULL COMMENT '父图表ID（用于多轮下钻血缘）'`

### 5.3 核心业务与接口设计

- **数据集模块（`bi-module-file-biz`）**：
  - `POST /api/dataset/upload`：上传 Excel/CSV，转为标准 CSV，生成列画像与前 20 行预览，落库并返回 `DatasetVO`。
  - `GET /api/dataset/my/list`：分页查询当前用户的数据集列表。
  - `GET /api/dataset/get?id=...`：获取数据集详情、列画像与前 20 行预览。
  - `POST /api/dataset/delete`：校验归属后逻辑删除。
- **分析引擎过滤能力扩展（`bi-module-analysis-biz`）**：
  - 扩展 `AnalysisPlan`，新增可选 `filters: List<FilterCondition>`（`field`、`operator`: `EQUALS / NOT_EQUALS / IN / CONTAINS / GT / GTE / LT / LTE`、`values: List<String>`）。
  - `SpreadsheetAnalysisService` 在聚合前执行**本地确定性过滤**，校验过滤字段必须存在于表头中，并将“过滤前行数 -> 过滤后行数”和过滤规则写入 `AnalysisSummary.note`。
  - 新增 `POST /api/chart/gen/by-dataset`（基于已有数据集新建分析）与 `POST /api/chart/followup`（传入 `parentChartId` + `followUpGoal` + 可选 `chartType`，自动携带父图表的 `AnalysisSummary` 作为上下文规划下一轮下钻）。
- **前端交互升级**：
  - 新增“数据集管理”页面或选择器：新建分析时支持“上传新文件”或“选择已有数据集”。
  - 升级“我的图表”详情抽屉：
    - 顶部展示图表 + **AI 分析口径透明卡**（展示选定维度/指标/统计方式、过滤条件、`planReason`、`note`、模型与 Token 信息）；
    - 底部新增**多轮下钻追问框** + 2~3 个快捷追问按钮，提交后生成关联子图表并支持沿血缘链路切换查看。

### 5.4 Phase 3 验收标准

- 单元测试覆盖：`FilterCondition` 全操作符本地过滤精度、非法过滤字段拦截、多轮追问继承 `datasetId` 与 `parentChartId`、跨用户访问数据集/父图表返回 `NO_AUTH_ERROR`。
- 前后端联调通过“上传一次数据集 -> 生成主图 -> 连续 2 次下钻追问”完整闭环。

---

## 6. Phase 4：离线量化评测基准与仓库门面重构

### 6.1 离线对比评测套件（Evaluation Benchmark）

在 `yubi-backend-master/modules/bi-module-analysis-biz/src/test`（或独立 `eval/` 目录）建设可复现评测集：

- **评测数据集**：构造 20~25 个标准测试用例（覆盖计数、求和、均值、去重计数、二维堆叠、带过滤条件、含空值/货币符号脏数据、高基数 ID 陷阱、模糊提问触发 `NEEDS_CLARIFICATION`、500~2000 行大表），每条用例附带标准答案（Ground Truth 数值与期望图表类型）。
- **双方案对比**：
  - **Baseline（传统直接喂 CSV）**：模拟原版 Prompt 直接向大模型传入 CSV 并要求输出 ECharts JSON。
  - **Candidate（Epiph-BI V2 两阶段本地聚合）**：走 `AnalysisTaskApplicationService` 规划 + 本地全量计算 + 解读链路。
- **核心量化指标**（自动输出 Markdown 报告 `docs/EVAL-REPORT-V2.0.md`）：
  1. 数值精确准确率（Numeric Exact Match %）
  2. ECharts Option JSON 合法可渲染率（%）
  3. 平均 Prompt/Total Token 消耗与节省比例（%）
  4. 端到端耗时 P50 / P95（ms）与故障注入下的降级成功率（%）

### 6.2 仓库清理与门面升级

- **安全清理上游模板冗余代码**：盘点并移除 `bi-infra` 中未被业务引用的 `Post*`（Mapper/EsDao）、`WxOpenConfig` 及教学用 `mq/SingleProducer`、`FanoutConsumer` 等 Demo 类（保留 `BiMessageProducer/Consumer` 等真实业务代码），确保全量编译与测试通过。
- **重写根目录与后端 `README.md`**：
  - 补全 V2.0 系统架构图、Plan-Compute-Explain 时序图、异步任务 CAS + 租约状态机图；
  - 附上 `EVAL-REPORT-V2.0.md` 实测对比表格与核心技术决策说明。

---

## 7. 非功能约束与风险控制

1. **向后兼容**：现有 `/api/chart/gen`、`/api/chart/gen/async`、`/api/chart/gen/async/mq`、`/api/chart/retry` 接口路径与核心响应结构保持兼容；旧任务仅存原始 CSV 时仍由 `SpreadsheetAnalysisService.restore` 兼容兜底。
2. **安全与脱敏**：所有错误信息、AI 日志与评测输出均不得包含真实 API Key 或个人敏感表数据；严格校验 `userId` 防止跨用户越权读取数据集或下钻父图表。
3. **测试门禁**：每个 Phase 编码完成后必须通过 `mvn test` 全量单测与前端 `pnpm build` 静态构建检查，再进入下一 Phase。
