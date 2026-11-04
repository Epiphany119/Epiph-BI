# 智能 BI 平台 V1.0

## 创建智能分析任务 PRD

| 项目 | 内容 |
|---|---|
| 产品名称 | 智能 BI 数据分析平台 |
| 文档版本 | V1.0 |
| 模块 | 创建智能分析任务 |
| 产品定位 | 企业级 BI 平台 + AI 异步任务系统 |
| 文档状态 | 待评审 |
| 创建日期 | 2026-09-20 |
| 目标用户 | 普通业务用户、数据分析师 |

## 0. 文档体系与 Maven 模块映射

本文作为 V1.0 核心业务 PRD，描述跨模块的用户目标、业务流程、数据契约和整体验收。每个 Maven 模块另建一份“分模块 PRD”，在同一文档中包含需求分析、职责边界、技术设计、包结构和模块验收标准。

文档与代码必须一一对应：

```text
总体 PRD / 总体技术设计
        ↓ 拆分
分模块 PRD（需求 + 技术设计 + 验收）
        ↓ 对应
Maven artifactId
        ↓ 对应
Java package + 模块测试
```

| Maven 模块 | 分模块 PRD | 当前状态 |
|---|---|---|
| `bi-common` | `PRD-V1.0-bi-common.md` | 待编写 |
| `bi-infra` | `PRD-V1.0-bi-infra.md` | 待编写 |
| `bi-ai-runtime` | `PRD-V1.0-bi-ai-runtime.md` | 初稿 |
| `bi-module-user-biz` | `PRD-V1.0-bi-module-user-biz.md` | 待编写 |
| `bi-module-auth-biz` | `PRD-V1.0-bi-module-auth-biz.md` | 待编写 |
| `bi-module-analysis-biz` | `PRD-V1.0-bi-module-analysis-biz.md` | 初稿 |
| `bi-module-chart-biz` | `PRD-V1.0-bi-module-chart-biz.md` | 需求与技术方案已定稿，实施中 |
| `bi-module-file-biz` | `PRD-V1.0-bi-module-file-biz.md` | 待编写 |
| `bi-module-dashboard-biz` | `PRD-V1.0-bi-module-dashboard-biz.md` | 待编写 |
| `bi-module-report-biz` | `PRD-V1.0-bi-module-report-biz.md` | 待编写 |
| `bi-web` | `PRD-V1.0-bi-web.md` | 待编写 |

模块边界总表见 `模块边界-V1.0.md`。总体 PRD 决定“系统需要完成什么”，分模块 PRD 决定“由哪个模块完成、如何实现、如何验收”。若两者冲突，必须先更新总体决策，再同步更新受影响的分模块 PRD，禁止只改代码不改文档。

## 1. 背景与问题

业务人员通常需要先整理 Excel/CSV 数据，再手动选择图表、观察趋势并撰写分析结论。这个过程依赖专业人员，耗时较长，且分析结果难以沉淀和复用。

当前系统已经具备文件上传、AI 分析、ECharts 图表和 RabbitMQ 异步处理能力，但需要将这些能力收敛为标准化、可追踪、可重试的任务流程，形成可用于生产环境演进的核心业务链路。

## 2. 产品目标

### 2.1 用户目标

- 用户可以上传结构化数据并填写业务分析目标。
- 系统根据文件大小自动选择同步或异步执行，用户无需理解或选择执行模式。
- 用户可以清楚知道任务当前处于什么状态。
- 用户可以查看图表、分析结论和失败原因。
- 用户可以在任务失败后安全重试，而不会产生重复结果。

### 2.2 系统目标

- 将 AI 分析请求抽象成具有明确生命周期的任务。
- 将耗时 AI 调用从 HTTP 请求中解耦。
- 保证任务状态最终一致，支持重复消息和网络异常。
- 保障用户数据隔离、接口鉴权和敏感信息安全。
- 为后续数据集、仪表盘、权限和审计模块提供稳定基础。

### 2.3 V1.0 非目标

- 不实现多图表拖拽仪表盘。
- 不实现团队协作和组织架构。
- 不实现复杂 SQL 数据源连接。
- 不实现自动数据清洗和机器学习建模。
- 不实现跨租户数据共享。

## 3. 用户角色与权限

| 角色 | 权限 |
|---|---|
| 普通用户 | 创建、查看、取消、重试和删除自己的分析任务 |
| 数据分析师 | 具备普通用户权限，可查看授权数据集 |
| 管理员 | 查看任务运行概况，处理异常任务，具备管理权限 |

权限原则：默认拒绝跨用户访问；任务、上传文件、分析结果必须通过当前登录用户进行归属校验。

## 4. 用户故事

### US-001 创建分析任务

作为业务用户，我希望上传一份销售数据并描述分析目标，以便系统自动生成趋势图和结论。

### US-002 查看任务状态

作为业务用户，我希望看到任务是等待、执行、成功还是失败，以便判断何时可以查看结果。

### US-003 失败重试

作为业务用户，我希望在 AI 服务临时失败时重试任务，而不必重新填写表单。

### US-004 查看分析结果

作为业务用户，我希望同时查看可视化图表和文字结论，以便直接用于业务判断。

### US-005 取消任务

作为业务用户，我希望取消仍在等待中的任务，避免浪费 AI 调用资源。

## 5. 业务流程

### 5.1 正常流程

1. 用户进入“创建分析”页面。
2. 用户上传 CSV 或 XLSX 文件。
3. 系统校验文件格式、大小和内容。
4. 用户填写分析目标、图表名称和图表类型。
5. 系统校验用户限流和权限，并按文件大小选择执行模式：不超过 2MB 先同步执行，超过 2MB 且不超过 5MB 进入异步队列。
6. 系统创建统一分析任务并生成唯一任务编号。
7. 同步执行在前端请求内完成；异步执行将任务投递到 RabbitMQ。
8. 同步执行连接失败时自动重连，最多 3 次，每次等待不超过 5 秒；仍未恢复则转入异步队列，禁止重新创建任务。
9. 系统更新任务状态并保存图表配置、分析结论和执行信息。
10. 前端根据任务状态直接展示结果，或引导用户进入任务详情。

### 5.1.1 自适应执行策略

| 条件 | 首选模式 | 失败后的处理 |
|---|---|---|
| 文件大小 ≤ 2MB | 同步 | 连接失败自动重连最多 3 次；仍失败则转异步 |
| 2MB < 文件大小 ≤ 5MB | 异步 | RabbitMQ 按异步重试策略执行 |
| 文件大小 > 5MB | 不执行 | 拒绝上传 |

2MB 是 V1.0 的可配置初始阈值，后续根据文件行数、解析耗时、AI 响应耗时、超时率和系统负载实测调整。该阈值不向用户展示。同步重连的单次等待上限为 5 秒，三次重连最多增加 15 秒等待时间。转异步时沿用原任务号和幂等键，前端不需要重新提交文件。

### 5.2 异步流程

```text
用户提交
  ↓
创建 WAITING 任务
  ↓
消息入队
  ↓
消费者领取任务
  ↓
RUNNING
  ↓
AI 调用
  ├─ 成功 → SUCCEEDED → 保存结果
  ├─ 可重试异常 → WAITING → 延迟重试
  ├─ 不可重试异常 → FAILED → 保存原因
  └─ 用户取消 → CANCELED
```

## 6. 任务状态机

| 状态 | 说明 | 可进入状态 | 可执行操作 |
|---|---|---|---|
| CREATED | 已创建，参数和文件校验通过，尚未开始执行 | RUNNING、WAITING、FAILED | 查看 |
| WAITING | 等待异步队列执行，或同步失败后等待转入队列 | RUNNING、CANCELED、FAILED | 取消 |
| RUNNING | 正在调用 AI 或处理数据 | SUCCEEDED、WAITING、FAILED、CANCELED | 查看状态 |
| SUCCEEDED | 已成功生成结果 | - | 查看、删除 |
| FAILED | 执行失败 | WAITING | 重试、查看原因、删除 |
| CANCELED | 用户主动取消 | - | 查看、删除 |

状态更新必须经过合法状态转换，禁止直接从 SUCCEEDED 或 CANCELED 回到 RUNNING。同步重连期间保持 RUNNING；同步重连 3 次失败后，复用原任务转为 WAITING 并切换为 ASYNC。V1.0 不增加 RETRYING 主状态，重试通过 WAITING、retry_count 和 last_error_code 表达。每次状态变更记录操作时间、操作来源和执行消息。

## 7. 页面需求

### 7.1 创建分析页面

| 字段 | 类型 | 必填 | 规则 |
|---|---|---|---|
| 原始数据 | CSV/XLSX 文件 | 是 | 单文件，默认不超过 10MB；后端再次校验 |
| 分析目标 | 多行文本 | 是 | 5-500 字，禁止纯空格 |
| 图表名称 | 文本 | 否 | 不超过 100 字，未填写时自动生成 |
| 图表类型 | 下拉框 | 否 | 折线图、柱状图、饼图、散点图、雷达图、自动识别 |
| 执行模式 | 系统计算 | 否 | 用户不可见，由文件大小和运行策略自动决定 |

页面状态必须覆盖：初始、上传中、校验中、提交中、处理中、成功、失败、空状态。

### 7.2 任务详情页面

展示内容：

- 任务编号
- 任务名称
- 创建人
- 创建时间、开始时间、完成时间
- 当前状态
- 重试次数
- 分析目标
- 数据文件摘要
- AI 分析结论
- ECharts 图表
- 错误码和错误信息
- 操作记录

按钮规则：

- WAITING：显示取消。
- RUNNING：默认只读；是否可取消由后端执行能力决定。
- FAILED：显示重试。
- SUCCEEDED：显示查看、删除、导出入口。
- CANCELED：只显示查看和删除。

## 8. 校验与异常处理

| 场景 | 系统行为 | 用户提示 |
|---|---|---|
| 未登录 | 拒绝请求并跳转登录 | 请先登录 |
| 文件为空 | 拒绝提交 | 请上传数据文件 |
| 文件格式错误 | 拒绝提交 | 仅支持 CSV 或 XLSX 文件 |
| 文件超限 | 拒绝提交 | 文件大小不能超过 5MB |
| 数据为空或无法解析 | 创建失败 | 文件中没有可分析的数据 |
| 分析目标为空 | 拒绝提交 | 请输入分析目标 |
| 超过用户限流 | 拒绝提交 | 操作过于频繁，请稍后再试 |
| RabbitMQ 投递失败 | 任务标记 FAILED | 任务提交失败，请重试 |
| 同步连接失败 | 自动重连 | 正在重试连接，最多 3 次，每次不超过 5 秒 |
| 同步重连仍失败 | 转入异步队列 | 服务响应较慢，任务已转入后台处理 |
| AI 超时 | 按策略重试或转异步 | AI 服务响应超时，系统将自动重试 |
| AI 返回格式错误 | 记录原始响应并失败 | AI 返回结果格式异常 |
| 重复消费消息 | 忽略重复执行 | 用户无感知 |
| 无权访问任务 | 返回 403 | 无权访问该任务 |

## 9. 可靠性要求

- 每个任务具有唯一任务 ID 和幂等键。
- 消费者执行前检查任务是否已经处于终态。
- AI 调用设置连接超时、读取超时和最大执行时长。
- ≤2MB 的同步任务连接失败时最多自动重连 3 次，单次等待不超过 5 秒；超过次数后转入异步队列。
- 异步任务可重试异常最多自动重试 3 次，采用递增延迟。
- 同步转异步必须复用原任务记录、taskNo 和幂等键，不得重复创建任务或重复调用 AI。
- 不可重试异常直接进入 FAILED。
- 任务状态和结果保存必须在同一业务事务内完成。
- 任务主状态仅使用 CREATED、WAITING、RUNNING、SUCCEEDED、FAILED、CANCELED；执行模式和执行阶段单独记录。
- RabbitMQ 消费失败进入死信或错误记录，不允许静默丢失。
- 文件临时资源在任务完成或失败后清理。
- 日志中不得打印密码、AccessKey、完整文件内容或 AI 密钥。

## 10. 数据模型

### 10.1 chart_task（建议新增）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT | 主键 |
| task_no | VARCHAR(32) | 业务任务号，唯一 |
| user_id | BIGINT | 创建人 |
| dataset_name | VARCHAR(255) | 原始文件名 |
| dataset_path | VARCHAR(500) | 文件存储地址，不返回给前端 |
| goal | VARCHAR(500) | 分析目标 |
| chart_name | VARCHAR(100) | 图表名称 |
| chart_type | VARCHAR(30) | 图表类型 |
| mode | VARCHAR(20) | SYNC / ASYNC |
| status | VARCHAR(20) | CREATED / WAITING / RUNNING / SUCCEEDED / FAILED / CANCELED |
| execution_stage | VARCHAR(30) | FILE_PARSING / AI_CALLING / SAVING_RESULT 等执行阶段 |
| retry_count | INT | 已重试次数 |
| reconnect_count | INT | 当前同步执行的重连次数 |
| error_code | VARCHAR(50) | 错误码 |
| error_message | VARCHAR(1000) | 用户可见错误信息 |
| started_at | DATETIME | 开始时间 |
| finished_at | DATETIME | 完成时间 |
| create_time | DATETIME | 创建时间 |
| update_time | DATETIME | 更新时间 |
| is_delete | TINYINT | 逻辑删除 |

索引要求：`uk_task_no(task_no)`、`idx_user_status(user_id,status)`、`idx_create_time(create_time)`。

### 10.2 chart_result（建议新增）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT | 主键 |
| task_id | BIGINT | 任务 ID |
| gen_chart | JSON/TEXT | ECharts 配置 |
| gen_result | TEXT | AI 分析结论 |
| raw_response | TEXT | 脱敏后的原始响应，可选 |
| result_version | INT | 结果版本 |
| create_time | DATETIME | 创建时间 |

### 10.3 chart_task_event（建议新增）

用于保存任务状态变更、同步重连、转异步、消息投递和用户操作等关键事件。`chart_task` 保存当前快照，`chart_task_event` 保存完整时间线。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT | 主键 |
| task_id | BIGINT | 任务 ID |
| task_no | VARCHAR(32) | 任务号 |
| event_type | VARCHAR(40) | 事件类型 |
| from_status | VARCHAR(20) | 变更前状态 |
| to_status | VARCHAR(20) | 变更后状态 |
| execution_mode | VARCHAR(20) | 事件发生时的执行模式 |
| execution_stage | VARCHAR(30) | 文件解析、AI 调用、结果保存等阶段 |
| attempt | INT | 异步执行尝试次数 |
| reconnect_count | INT | 同步重连次数 |
| error_code | VARCHAR(50) | 脱敏错误码 |
| error_message | VARCHAR(1000) | 脱敏错误信息 |
| duration_ms | BIGINT | 阶段耗时 |
| operator_type | VARCHAR(20) | USER / SYSTEM / CONSUMER / ADMIN |
| operator_id | BIGINT | 操作人，可为空 |
| trace_id | VARCHAR(64) | 链路追踪 ID |
| metadata | JSON/TEXT | 扩展信息，不得存密钥、密码、Token、原始文件内容和完整 Prompt |
| create_time | DATETIME | 事件发生时间，事件默认永久保存 |

事件表至少记录：`TASK_CREATED`、`TASK_ROUTED_SYNC`、`TASK_ROUTED_ASYNC`、`SYNC_CONNECTION_FAILED`、`SYNC_RECONNECT_STARTED`、`SYNC_RECONNECT_EXHAUSTED`、`SYNC_FALLBACK_TO_ASYNC`、`ASYNC_MESSAGE_PUBLISHED`、`ASYNC_MESSAGE_CONSUMED`、`ASYNC_RETRY_SCHEDULED`、`TASK_SUCCEEDED`、`TASK_FAILED`、`TASK_CANCELED` 和用户重试、取消操作。

任务状态更新与事件写入必须在同一个事务中完成，禁止出现任务状态已改变但事件缺失的情况。

V1.0 事件记录永久保存，不执行定期清理，以保证简历项目中的任务链路、失败原因和同步转异步过程始终可追溯。当未来事件量显著增长时，再设计按时间分区、冷热归档或合规保留周期，不在 V1.0 引入清理任务。

## 11. 接口契约

### 11.1 创建任务

`POST /api/chart/task`

请求：`multipart/form-data`

| 参数 | 类型 | 必填 |
|---|---|---|
| file | MultipartFile | 是 |
| goal | String | 是 |
| name | String | 否 |
| chartType | String | 否 |
| mode | String | 是 |
| idempotencyKey | String | 是 |

返回：

```json
{
  "code": 0,
  "data": {
    "taskNo": "AT202609200001",
    "status": "WAITING"
  },
  "message": "ok"
}
```

### 11.2 查询任务

`GET /api/chart/task/{taskNo}`

只允许任务创建人或管理员访问。

### 11.3 取消任务

`POST /api/chart/task/{taskNo}/cancel`

仅允许 WAITING，或系统明确支持取消的 RUNNING 任务。

### 11.4 重试任务

`POST /api/chart/task/{taskNo}/retry`

仅允许 FAILED；重试后状态变为 WAITING，retry_count 加一。

### 11.5 删除任务

`DELETE /api/chart/task/{taskNo}`

采用逻辑删除，结果数据是否物理清理由后台策略决定。

## 12. 非功能指标

| 指标 | V1.0 目标 |
|---|---|
| 创建任务接口响应 | 95% 请求在 1 秒内返回 |
| 任务状态可见延迟 | 不超过 5 秒 |
| 文件上传限制 | 单文件 5MB，单用户并发任务不超过 3 个 |
| 同步重连 | 最多 3 次，单次不超过 5 秒 |
| 异步自动重试 | 最多 3 次 |
| 数据隔离 | 100% 按 user_id 校验 |
| 可观测性 | 任务创建、状态变更、失败、重试均有日志 |

## 13. 验收用例

### AC-001 正常异步创建

**Given** 用户已登录且文件合法；  
**When** 用户提交异步分析；  
**Then** 接口返回 taskNo 和 WAITING，消息进入队列，最终展示图表和分析结论。

### AC-002 文件校验

**Given** 用户上传超过 5MB 或不支持格式的文件；  
**When** 用户点击提交；  
**Then** 不创建任务，页面展示明确错误原因。

### AC-003 重复提交

**Given** 用户使用相同 idempotencyKey 重复请求；  
**When** 服务端收到第二次请求；  
**Then** 返回原任务，不创建重复任务。

### AC-004 消息重复消费

**Given** 同一任务消息被投递两次；  
**When** 消费者处理消息；  
**Then** 仅允许一次有效执行，结果只有一个有效版本。

### AC-005 AI 可重试异常

**Given** AI 服务发生超时；  
**When** 任务执行失败；  
**Then** 异步任务按递增延迟最多重试 3 次，超过次数后进入 FAILED。

### AC-008 同步失败转异步

**Given** 文件不超过 2MB，系统选择同步执行；  
**When** 同步连接失败；  
**Then** 系统最多自动重连 3 次，每次等待不超过 5 秒；仍失败时复用原任务转入 RabbitMQ，状态变为 WAITING，不创建重复任务。

### AC-006 越权访问

**Given** 用户 A 已登录；  
**When** 用户 A 查询用户 B 的 taskNo；  
**Then** 返回无权访问，不泄露任务是否存在及任务内容。

### AC-007 取消任务

**Given** 任务处于 WAITING；  
**When** 用户点击取消；  
**Then** 状态变为 CANCELED，消费者不得继续执行该任务。

## 14. 评审决策项

以下内容需要在进入技术设计前确认：

1. 同步与异步并存，用户透明；系统按 2MB 初始阈值自动路由。
2. 文件上限统一为 5MB。
3. 同步连接失败最多重连 3 次，每次不超过 5 秒，仍失败转异步；异步可重试异常自动重试最多 3 次，并支持手动重试。
4. 任务结果允许保留多个版本。
5. V1.0 引入独立 `chart_task` 和 `chart_result` 表。
6. 支持取消 WAITING 任务；RUNNING 任务仅在执行器支持安全取消时允许取消。

## 15. 变更记录

| 日期 | 版本 | 修改内容 | 修改人 |
|---|---|---|---|
| 2026-09-20 | V1.0 | 初始版本，定义创建智能分析任务的业务、技术和验收要求 | 产品/研发 |
| 2026-09-20 | V1.1 | 确定同步/异步对用户透明，2MB 自动路由；同步失败重连 3 次、单次 5 秒后转入异步 | 产品/研发 |
| 2026-09-21 | V1.2-draft | 增加总体 PRD、分模块 PRD、Maven 模块、Java 包和测试的一一映射规范 | 产品/研发 |
