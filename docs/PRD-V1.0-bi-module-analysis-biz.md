# 智能 BI 平台 V1.0

## `bi-module-analysis-biz` 分模块 PRD

| 项目 | 内容 |
|---|---|
| Maven 模块 | `modules/bi-module-analysis-biz` |
| ArtifactId | `bi-module-analysis-biz` |
| 模块定位 | 智能分析任务业务编排 |
| 版本 | V1.0-draft |
| 上游依赖 | `bi-common`、`bi-infra`、`bi-ai-runtime`、`bi-module-user-biz`、`bi-module-chart-biz` |
| 文档状态 | 初稿，待评审 |

## 1. 需求分析

本模块承接用户发起的“上传结构化文件并生成分析结果”流程，负责把文件、分析目标和图表类型组织为一次完整的分析任务。模块必须调用统一的 `AiChatClient`，不得直接依赖智谱 SDK 或供应商 HTTP 协议。

### 1.0 当前实现问题

当前实现曾将整份表格文本直接拼接到 Prompt。5MB 文件上限并不代表文本能放入模型上下文，因此约 100KB 的表格也可能因行列内容和 Prompt 开销而超时、被拒绝或截断。V1.0 MVP 改为本地读取并执行全量分组统计，只把最多 20 个聚合结果交给模型解释；图表数值由本地统计结果生成。

### 1.1 用户目标

- 上传 CSV/XLSX 文件并填写分析目标；
- 获得图表配置和文字分析结论；
- 查看任务状态和失败原因；
- 对失败任务进行手动重试；
- 只能访问本人创建的任务和结果。

### 1.2 V1.0 范围

- 以同步分析为主；
- 保留异步 MQ 扩展点，但不在本模块首轮实现复杂自动重试；
- 继续使用现有 `chart` 表保存分析结果；
- 负责 CSV/XLSX 内容读取、字段识别和本地精确聚合；
- 只将紧凑统计摘要交给模型生成 `genResult` 结论；
- 根据本地聚合值构造图表配置，不采信模型生成的图表数值；
- 失败任务明确记录为 `FAILED`。

### 1.3 非目标

- 不实现 AI 供应商调用、API Key 和 HTTP 协议；
- 不实现登录、注册和用户认证；
- 不实现 COS/Redis/RabbitMQ 底层连接；
- 不实现多图表仪表盘和复杂数据清洗；
- 不实现流式 Token 展示；
- 不实现复杂自动重试策略。
- 不实现多维交叉分析、自动分片、多轮模型总结；V1.0 通过单维度本地聚合控制模型输入规模。
- 不实现结构化查询或 SQL Agent；该能力作为后续版本目标。

## 2. 模块边界

| 能力 | 归属 |
|---|---|
| 分析任务参数校验、流程编排、结果落库 | `bi-module-analysis-biz` |
| Excel/CSV 读取和分析文本转换 | `bi-module-analysis-biz`，底层库可由 `bi-infra` 提供 |
| AI 调用、模型选择、供应商错误映射 | `bi-ai-runtime` |
| 文件上传、对象存储 | `bi-module-file-biz` / `bi-infra` |
| 用户身份和归属校验 | `bi-module-user-biz` / 公共认证能力 |
| 消息队列、Redis、数据库连接 | `bi-infra` |
| 前端图表渲染 | 前端应用 |

核心边界：

```text
analysis-biz 负责“分析什么、结果是否符合业务要求、任务如何结束”
ai-runtime   负责“如何调用模型、上游返回了什么文本”
```

## 3. 核心流程

```text
Controller
   ↓
AnalysisApplicationService
   ├─ 校验登录用户和文件
   ├─ 读取 CSV/XLSX 并识别字段
   ├─ 对全部数据行进行本地分组、计数/求和/平均值
   ├─ 持久化紧凑统计摘要，供失败重试复用
   ├─ 只将摘要交给 AiChatClient 生成结论
   ├─ 根据本地聚合值构造 ECharts 配置
   └─ 保存图表和结论并返回结果
```

V1.0 首轮默认同步执行。后续保留 `BiMessageConsumer` 作为异步入口时，消费者必须复用同一个应用服务，不能复制一套 Prompt 和解析逻辑。

## 4. 任务状态

```text
WAITING  → RUNNING → SUCCEEDED
                  └→ FAILED
```

- 创建任务后进入 `WAITING`；
- 开始调用 AI 前进入 `RUNNING`；
- 业务结果解析和保存成功后进入 `SUCCEEDED`；
- 参数、文件、AI 调用或业务结果解析失败后进入 `FAILED`；
- V1.0 暂不增加复杂 `RETRYING` 状态，手动重试重新执行任务并覆盖失败信息。

## 5. 业务输入与输出

### 5.1 输入

- 文件：CSV 或 XLSX，大小和行数受统一配置限制；
- `name`：图表名称；
- `goal`：分析目标，必填；
- `chartType`：期望图表类型，可选；
- 当前登录用户 ID：由认证上下文获取，不允许由前端任意指定。

### 5.1.1 V1.0 数据入口限制

| 项目 | 限制 |
|---|---:|
| 文件类型 | CSV、XLSX |
| 文件大小 | 最大 5MB |
| 工作表 | 第一个非空工作表 |
| 最大列数 | 50 列 |
| 最大数据行 | 10,000 行 |
| 单元格最大长度 | 1,000 字符 |
| 转换后文本 | 最大 1,000,000 字符；该文本只在服务端本地处理，不发送给模型 |

超过任一限制时直接拒绝，并向用户说明实际值和允许值。V1.0 禁止静默截断或只取前若干行继续分析，避免模型基于不完整数据生成误导性结论。

读取结果在服务端转换为标准 CSV，并在本地扫描全部数据行。模型输入是结构化摘要：包括行数、分组字段、统计方式、最多 20 个聚合值及数据质量说明，不包含原始行。不得把 Java 对象 `toString()` 作为模型输入。

### 5.1.2 数据转换要求

- V1.0 同时支持 `.xlsx` 和 `.csv`；
- XLSX 只读取第一个非空工作表；
- 保留空单元格的位置，不得通过过滤空值导致列错位；
- 转换阶段逐行统计行数、列数和字符数，达到限制立即终止；
- 文件大小限制只是入口限制；转换后文本最多 1,000,000 字符，并在服务端本地处理；
- 当前模型请求只包含最多 20 个分组结果，避免原始表格规模决定 Token 消耗；
- 超限错误必须说明超限维度，不允许静默丢弃数据。

### 5.2 业务结果

模型文本必须最终解析为只含结论的 JSON：

```json
{
  "genResult": "基于本地统计摘要撰写的分析结论"
}
```

服务端根据同一统计摘要本地生成 `genChart`，以确保图表类别和值来自上传文件的全量计算。为兼容历史任务，解析器仍可读取旧格式中的 `genChart`，但新任务不使用模型生成的图表。

解析器兼容历史 Markdown 代码块和旧分隔符；新契约只校验非空 `genResult`。图表 JSON 由服务端产生并校验，不允许模型输出中的示例图表覆盖本地结果。解析失败时任务进入 `FAILED`，V1.0 不自动再次调用 AI。

## 6. AI 调用契约

```java
AiChatRequest request = new AiChatRequest();
request.setSystemPrompt(analysisSystemPrompt);
request.setUserMessage(userInput);
request.setTraceId(taskId.toString());
AiChatResponse response = aiChatClient.chat(request);
String modelContent = response.getContent();
```

本模块 V1.0 不主动设置 `model`，使用 Runtime 配置的默认模型；不读取智谱 DTO，不处理 HTTP 状态码。

## 7. 失败处理

| 场景 | 任务结果 | 处理 |
|---|---|---|
| 文件格式或参数错误 | 不创建成功任务 | 直接返回参数错误 |
| API Key/模型配置错误 | `FAILED` | 记录统一错误码，提示配置问题 |
| AI 限流、超时、网络异常 | `FAILED` | 保留 `retryable` 信息，V1.0 不自动无限重试 |
| AI 返回格式错误 | `FAILED` | 保存脱敏错误摘要，不保存完整模型响应 |
| 数据库保存失败 | `FAILED` 或事务回滚 | 记录日志并告警 |

## 7.1 MVP 接口闭环

V1.0 至少提供以下业务能力，接口路径可沿用现有 `/api/chart` 路由：

| 能力 | 说明 | 成功结果 |
|---|---|---|
| 创建并同步分析 | 上传文件、提交目标和图表类型 | 返回任务/图表 ID、`genChart`、`genResult` |
| 查询任务详情 | 只能查询当前用户拥有的任务 | 返回状态、输入摘要、结果或失败原因 |
| 查询我的任务 | 分页查询当前用户任务 | 返回任务列表和状态 |
| 手动重试 | 仅允许重试本人 `FAILED` 任务 | 重新进入 `WAITING` 并执行一次 |
| 删除任务 | 只能删除本人任务 | 逻辑删除，不影响其他用户 |

V1.0 的同步创建接口必须覆盖完整事务链：参数校验 → 数据读取 → Prompt 组装 → AI 调用 → 业务结果解析 → `chart` 保存 → 返回结果。任何中途失败都必须有明确错误响应，并将已创建任务更新为 `FAILED`。

## 7.2 持久化要求

继续使用现有 `chart` 表作为 V1.0 任务和结果载体，至少保证以下字段语义：

| 字段语义 | 用途 |
|---|---|
| `id` | 任务/图表唯一标识 |
| `userId` | 用户归属和权限隔离 |
| `name`、`goal`、`chartType` | 用户输入 |
| `chartData` | 经限制和校验后的结构化数据文本 |
| `genChart`、`genResult` | 成功结果 |
| `status` | `WAITING`、`RUNNING`、`SUCCEEDED`、`FAILED` |
| `execMessage` | 脱敏失败原因或执行摘要 |
| `isDelete`、时间字段 | 逻辑删除和审计 |

保存成功结果和状态必须在同一业务事务边界内完成；失败信息不得保存 API Key、完整 Prompt 或完整模型响应。

## 7.3 V1.0 接口契约

V1.0 可以沿用现有 `/api/chart` 路由，接口语义必须满足以下契约：

| 接口能力 | 建议接口 | 关键规则 |
|---|---|---|
| 创建并同步分析 | `POST /api/chart/gen` | 上传文件、目标和图表类型；成功返回结果，失败返回明确错误 |
| 查询任务详情 | `GET /api/chart/get?id={id}` | 查询条件必须包含当前登录用户归属 |
| 查询我的任务 | `POST /api/chart/my/list/page` | 服务端强制使用当前用户 ID，不接受前端 userId 作为权限依据 |
| 手动重试 | `POST /api/chart/retry?id={id}` | 仅允许本人 `FAILED` 任务 |
| 删除任务 | `POST /api/chart/delete` | 逻辑删除；SQL 必须同时校验 id 和当前用户 ID |

创建成功响应至少包含：

```json
{
  "id": 123,
  "status": "SUCCEEDED",
  "genChart": {},
  "genResult": "分析结论"
}
```

任务详情和列表响应至少包含：

```text
id、name、goal、chartType、status、execMessage、genChart、genResult、createTime、updateTime
```

详情、列表、重试和删除接口均必须从登录上下文获得用户 ID，禁止信任前端传入的 `userId`。

## 7.4 状态、权限和重复执行保护

### 状态流转

```text
WAITING → RUNNING → SUCCEEDED
                  └→ FAILED
```

- 只有 `WAITING` 任务可以进入 `RUNNING`；
- 只有 `RUNNING` 任务可以进入 `SUCCEEDED` 或 `FAILED`；
- `FAILED` 任务手动重试时回到 `WAITING`；
- `SUCCEEDED`、`RUNNING`、`WAITING` 任务不能通过“失败重试”接口重复执行；
- 状态更新必须带原状态条件，避免重复消息或并发请求覆盖结果。

### 用户归属

任务详情、列表、重试和删除都必须执行：

```text
任务.userId == 当前登录用户.id
```

推荐将归属条件直接放入数据库查询，例如：

```sql
SELECT * FROM chart
WHERE id = ? AND userId = ? AND isDelete = 0;
```

不允许只按任务 ID 查询后再遗漏权限判断，也不允许允许前端传入任意用户 ID。

### 重复执行保护

- 重复消息消费时，只有成功将 `WAITING` 更新为 `RUNNING` 的执行者可以继续调用 AI；
- 发现任务已处于 `RUNNING` 或 `SUCCEEDED` 时，重复消费者必须直接结束；
- 只有 `RUNNING` 任务可以写入成功结果；
- 成功结果写入和状态更新必须处于同一事务边界；
- V1.0 手动重试只针对 `FAILED` 任务，暂不设计通用请求幂等号。

### 删除规则

- 删除使用逻辑删除，不物理删除数据库记录；
- 删除 SQL 必须同时包含 `id` 和当前用户 ID；
- `RUNNING` 任务 V1.0 暂不允许删除；
- 删除后详情和列表均不可见；
- 删除操作不影响其他用户任务。

## 8. 包结构建议

```text
com.yupi.springbootinit.analysis
├── controller
├── application
│   └── AnalysisTaskApplicationService
├── service
├── parser
│   └── AnalysisResultParser
├── prompt
│   └── AnalysisPromptBuilder
├── converter
│   └── SpreadsheetDataConverter
├── model
├── mapper
└── bizmq
```

现有 Controller、消费者和解析器可渐进迁入上述职责，不要求首轮一次性重命名全部历史类。

### 8.1 旧代码迁移顺序

```text
第一步：建立 AnalysisPromptBuilder、AnalysisResultParser、SpreadsheetDataConverter
第二步：建立 AnalysisTaskApplicationService，集中完整分析流程
第三步：ChartController 改为调用应用服务
第四步：BiMessageConsumer 改为调用同一应用服务
第五步：确认无引用后删除旧 AiManager 和对应测试
```

目标依赖关系：

```text
ChartController ───────────────┐
                               ▼
                 AnalysisTaskApplicationService
                               ├─ SpreadsheetDataConverter
BiMessageConsumer ─────────────┤─ AnalysisPromptBuilder
                               ├─ AiChatClient
                               ├─ AnalysisResultParser
                               └─ ChartApplicationService
                                  （受限任务上下文 / 状态结果 DTO）
```

`ChartApplicationService` 由 `bi-module-chart-biz` 提供。analysis-biz 只通过受限的任务上下文和状态/结果 DTO 读写图表，不依赖 `ChartService`、`ChartMapper`，也不直接持有可任意修改的 Chart 持久化实体。调用 AI 的长事务不得包住任务状态更新；创建、领取、成功、失败和重试状态分别通过短事务持久化，确保 AI 异常后 FAILED 不会因外层事务回滚而丢失。Controller 只负责 HTTP 参数、登录用户和响应封装；MQ Consumer 只负责接收任务标识和确认消息。两者不得各自维护 Prompt、AI 调用和结果解析代码。

旧 `AiResponseParser` 的能力迁移为 `analysis/parser/AnalysisResultParser`，对外返回明确的 `AnalysisResult`，不再使用无语义的字符串数组。旧 `AiManager` 在所有调用方切换到 `AiChatClient` 后删除。

## 9. 测试要求

- 参数和文件格式校验测试；
- 文件大小、行数、列数、单元格长度和转换文本长度超限测试；
- CSV/XLSX 空单元格位置保持测试；
- CSV/XLSX 转文本测试；
- Prompt 组装测试；
- 合法 `genChart/genResult` 解析测试；
- Markdown、旧分隔符和非法 JSON 解析测试；
- `AiChatClient` Mock 调用测试；
- AI Runtime 异常到 `FAILED` 的状态测试；
- 用户归属校验测试；
- 创建、详情、我的列表、重试、删除接口的应用服务测试；
- 状态流转和重复执行保护测试；
- 成功结果与 `chart` 持久化一致性测试；
- 失败后 `FAILED` 状态和脱敏 `execMessage` 测试；
- 不访问真实智谱 API 的模块单元测试。

真实 API 调用属于 `bi-web` Spring 集成测试，不放入本模块单元测试。

## 10. 验收标准

- 模块可以通过 `mvn -pl modules/bi-module-analysis-biz -am test`；
- 业务代码只依赖 `AiChatClient`，不出现智谱 HTTP 调用；
- 同步分析成功时可保存并返回图表配置和结论；
- 任意失败路径都有明确任务状态和错误信息；
- 解析器不把供应商协议细节泄露到业务接口；
- 模块测试不需要 API Key、Redis、RabbitMQ 或真实数据库。
- 用户可以完成“上传 → 分析 → 返回图表和结论 → 查询历史结果”的闭环；
- 失败任务可以查询失败原因并手动重试；
- 删除、详情和列表接口均执行用户归属校验；
- 同一任务不会因重复请求或重复消息产生重复成功结果。

### 10.1 端到端 MVP 验收

#### 成功链路

1. 用户登录；
2. 上传合法 CSV/XLSX 并填写分析目标；
3. 创建任务并进入 `WAITING` 或 `RUNNING`；
4. AI 调用成功并解析 `genChart/genResult`；
5. `chart` 记录保存成功，状态变为 `SUCCEEDED`；
6. 创建接口返回图表和结论；
7. 刷新页面后，通过我的任务列表找到该任务；
8. 详情接口返回与创建接口一致的结果。

#### 失败和重试链路

1. 模拟配置错误、超时或非法 AI 响应；
2. 任务状态变为 `FAILED`；
3. 详情接口返回脱敏失败原因；
4. 本人调用重试接口，任务重新进入 `WAITING`；
5. 成功后状态变为 `SUCCEEDED`。

#### 权限和删除链路

1. 用户 A 不能查询、重试或删除用户 B 的任务；
2. 删除成功后任务不再出现在列表和详情接口；
3. `RUNNING` 任务删除请求被拒绝。

## 12. V1.0 本地摘要边界

- 服务端读取并统计全部数据行，V1.0 支持一个分组维度下的记录数、数值求和或平均值；
- 分组数不超过 20 时全部呈现；条形图/饼图/雷达图超过 20 组时展示前 19 组并合并其余组，折线图展示按类别顺序排列的前 20 组并明确标注未绘制的分组；
- 模型只收到摘要并生成文字结论，不接收原始行、不负责计算或生成图表；
- 复杂多维分析、分片分析、Token 精确预算和跨分片总结留待后续版本评审。

## 13. 后续演进目标：结构化查询 / SQL Agent

V1.0 的本地摘要只覆盖单维度分组计数、数值求和和平均值；当数据规模和分析复杂度超过该能力后，系统可以演进为结构化查询模式：

```text
用户问题
    ↓
模型生成查询意图或受控 SQL
    ↓
系统校验 SQL 只读和字段权限
    ↓
临时表或数据集执行聚合查询
    ↓
查询结果交给模型解释
    ↓
生成图表配置和分析结论
```

例如“分析不同地点之间的店铺数量分布”最终可以转换为受控聚合查询：

```sql
SELECT location, COUNT(*)
FROM uploaded_dataset
GROUP BY location;
```

该方案的长期价值：

- 原始数据量不再直接受模型上下文限制；
- 统计结果由数据库保证准确性；
- 发送给模型的内容更小，Token 成本更低；
- 更适合数据集复用、权限控制和企业级 BI 查询。

该方案暂不属于 V1.0，因为还需要解决文件导入临时表、字段类型映射、SQL 只读校验、字段权限、查询超时和恶意 SQL 防护等问题。V1.0 先完成“本地读取 + 本地统计摘要 + 模型解释”的完整 MVP 闭环，后续以独立版本设计 SQL Agent。

## 11. 版本演进

- V1.0：同步分析、统一 Runtime 调用、结果解析、失败落库；
- V1.1：多维交叉统计、时间字段智能粒度和摘要策略增强；
- 后续版本：异步队列完整接入、自动重试、任务取消、结果版本和流式展示；
- 长期版本：结构化查询 / SQL Agent、数据集临时表、受控 SQL 聚合和查询权限体系。
