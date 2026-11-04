# bi-module-chart-biz V1.0 产品需求与技术设计

| 项目 | 内容 |
|---|---|
| 文档状态 | 需求与技术方案已定稿，按本文实施 |
| 对应 Maven 模块 | `modules/bi-module-chart-biz` |
| 本次测试边界 | 模块内单元/组件测试；全量 Spring 集成测试留到最终项目整合 |

## 1. 文档定位

`bi-module-chart-biz` 是智能 BI 系统的图表领域模块，负责图表实体、图表持久化、图表管理接口，以及向分析任务提供受控的图表读写能力。

V1.0 MVP 的目标是把当前放在 `bi-common`、`bi-infra`、`bi-module-analysis-biz` 的图表职责收敛到 `chart-biz`，让分析、看板和报表按业务服务使用图表能力。HTTP 路径和请求/响应 JSON 保持兼容，不改变数据库表结构。

## 2. 模块边界

### 2.1 本模块负责

- `Chart` 图表实体、MyBatis Mapper 接口和对应 XML；
- 图表创建、编辑、详情、分页查询和逻辑删除；
- 图表所属用户和管理员权限校验；
- 图表分析状态和结果的受控读写；
- 为 `bi-module-analysis-biz` 提供稳定的 `ChartApplicationService` 合约；
- 图表 CRUD HTTP 接口。

### 2.2 本模块不负责

- 文件上传、文件解析和 CSV/XLSX 转换；
- AI 分析任务执行、Prompt 构造、大模型调用、模型结果解析和重试编排；
- RabbitMQ 消费和异步任务调度；
- 看板布局和报表编排。

## 3. 用户需求

普通用户可以创建、查看、分页查询、编辑和删除自己的图表，不得查看或修改其他用户的图表。管理员可以分页查看全部未删除图表，并在权限范围内管理图表，但不能绕过逻辑删除和状态约束。

通用编辑接口只允许修改图表名称、分析目标、图表类型等用户可编辑字段。`chartData`、`genChart`、`genResult`、`status`、`execMessage` 和 `userId` 由分析应用服务或服务端身份上下文管理，不能通过普通编辑请求任意改写。

## 4. 图表生命周期

分析模块使用统一的大写状态。V1.0 只写入规范状态；读取历史数据时兼容旧版小写状态别名，并在下一次状态更新时写回规范状态：

```text
WAITING    待执行
RUNNING    执行中
SUCCEEDED  执行成功
FAILED     执行失败
```

状态流转：

```text
创建       -> WAITING
开始执行   -> RUNNING
执行成功   -> SUCCEEDED
执行失败   -> FAILED
失败重试   -> WAITING -> RUNNING
```

以上状态机适用于 AI 分析任务。普通手动创建图表按现有接口语义保存，不伪造 AI 执行状态；当前数据库默认值为旧值 `wait`，本次不改表结构，读取时兼容旧值，新建 AI 任务和后续状态更新使用大写规范状态。

运行中的图表不能删除；成功或失败图表可以逻辑删除；已删除图表默认不进入普通查询结果。

## 5. V1.0 接口需求

接口继续使用现有 `/api/chart` 前缀，保证前端兼容。

| 方法 | 路径 | 说明 | 归属 |
|---|---|---|---|
| POST | `/api/chart/add` | 手动创建图表 | chart-biz |
| POST | `/api/chart/delete` | 逻辑删除图表 | chart-biz |
| POST | `/api/chart/update` | 更新图表基础信息 | chart-biz |
| POST | `/api/chart/edit` | 编辑图表 | chart-biz |
| GET | `/api/chart/get` | 查询图表详情 | chart-biz |
| POST | `/api/chart/list/page` | 管理员分页查询 | chart-biz |
| POST | `/api/chart/my/list/page` | 当前用户分页查询 | chart-biz |
| POST | `/api/chart/gen` | 同步 AI 分析 | analysis-biz |
| POST | `/api/chart/gen/async` | 线程池异步 AI 分析 | analysis-biz |
| POST | `/api/chart/gen/async/mq` | MQ 异步 AI 分析 | analysis-biz |
| POST | `/api/chart/retry` | 失败分析重试 | analysis-biz |

### 5.1 CRUD 权限与查询约束

| 接口 | V1.0 权限和数据范围 |
|---|---|
| `/add` | 必须登录；所属用户由服务端写入，忽略请求中任何用户 ID |
| `/delete` | 本人或管理员；RUNNING 任务拒绝删除；执行逻辑删除 |
| `/update` | 管理员专用 |
| `/edit` | 本人或管理员；只允许修改明确的用户可编辑字段 |
| `/get` | 本人或管理员；只返回未逻辑删除的图表 |
| `/list/page` | 管理员专用；查询未逻辑删除图表。现有代码缺少此权限检查，迁移时补齐 |
| `/my/list/page` | 必须登录；userId 强制取当前登录用户，不信任请求参数；单页 pageSize 不超过现有上限 20 |

`/edit` 和 `/update` 都只允许修改 `name`、`goal`、`chartType` 等明确可编辑字段；`chartData`、AI 结果、状态、错误信息、所属用户和删除标记不能经 CRUD 请求写入。分页排序字段必须经过已有白名单校验。所有列表默认排除逻辑删除数据。

### 5.2 ChartApplicationService

图表 CRUD 由本模块的 Controller 和内部 `ChartService` 完成；跨模块公开的 `ChartApplicationService` 只提供分析任务需要的受控能力：

- 创建 WAITING 状态的分析图表，并将所属用户从服务端命令写入；
- 获取分析/重试所需的受限上下文（任务 ID、用户 ID、目标、图表类型、数据文本和当前状态）；
- `tryStartAnalysis`：仅允许 WAITING 任务原子转换到 RUNNING，返回是否成功取得执行权；
- `completeAnalysis`：仅允许 RUNNING 任务写入图表配置、分析结论并转换到 SUCCEEDED；
- `failAnalysis`：仅允许 RUNNING 任务写入脱敏错误并转换到 FAILED；
- `prepareRetry`：校验所属用户和 FAILED 状态，再将任务转换到 WAITING 并返回重试上下文。

对外 Java 合约固定为独立的创建命令、受限任务上下文和结果参数，不暴露 `Chart` 持久化实体或 `IService<Chart>`。分析模块不得获取 `ChartMapper`、执行通用 Chart CRUD 或直接持有可任意修改的持久化实体。状态更新必须按预期旧状态做条件更新，避免重复 MQ 消息或并发重试覆盖任务状态。

## 6. 数据语义

| 字段 | 语义 |
|---|---|
| `id` | 图表唯一标识 |
| `name` | 图表名称 |
| `goal` | 分析目标 |
| `chartData` | 转换后的结构化数据文本或任务输入摘要 |
| `chartType` | 用户指定或模型生成的图表类型 |
| `genChart` | ECharts 配置 JSON 字符串 |
| `genResult` | AI 生成的业务分析结论 |
| `status` | 图表/分析任务状态 |
| `execMessage` | 失败原因或执行说明，需脱敏并限制长度 |
| `userId` | 所属用户 |
| `isDelete` | 逻辑删除标识 |
| `createTime` / `updateTime` | 审计时间 |

## 7. 技术设计

```text
bi-module-chart-biz/src/main/java
└── com/yupi/springbootinit/                 # Chart/Mapper/DTO 暂保留现有 Java package/FQCN
    ├── controller/ChartManagementController.java # 仅图表 CRUD 路由
    ├── mapper/ChartMapper.java
    ├── model/entity/Chart.java
    └── model/dto/chart/                     # 图表 CRUD 专属 DTO
        ├── ChartAddRequest.java
        ├── ChartEditRequest.java
        ├── ChartUpdateRequest.java
        └── ChartQueryRequest.java

bi-module-chart-biz/src/main/java/com/yupi/springbootinit/modules/chart/application/
├── ChartApplicationService.java             # 面向 analysis-biz 的受控业务契约
└── ChartApplicationServiceImpl.java

bi-module-chart-biz/src/main/resources/mapper/ChartMapper.xml
```

Maven artifact 是模块归属边界。为降低迁移风险，`Chart`、`ChartMapper` 和图表 CRUD DTO 保留现有 Java 包名/FQCN，但源码文件、Mapper XML 和 DTO 迁入其负责的 `bi-module-chart-biz`。图表 Controller 会因 CRUD 与 AI 路由拆分而调整归属或类名，HTTP 路径和 JSON 合约保持兼容。现有继承 MyBatis-Plus `IService<Chart>` 的 `ChartService` 不作为跨模块 API；迁移时将它重构为上图所示的 `ChartApplicationService` 受控契约，避免向 analysis-biz 暴露任意 CRUD 能力。Mapper XML 放在 chart-biz 自己的 `src/main/resources/mapper/` 下。`bi-infra` 只保留数据源、MyBatis 通用配置、拦截器等不含具体业务实体的基础设施，不再引用 `Chart`。后续如需重组其余 Java package，应单独评估兼容性，不纳入本次迁移。

ChartMapper 现有 `queryChartData(String querySql)` 使用 `${querySql}` 拼接原始 SQL，当前代码没有找到调用方。该方法不属于图表管理 MVP，不迁入 chart-biz；迁移时从 Mapper 和 XML 中移除。未来 SQL Agent 需要执行查询时，另行设计只读连接、表/字段白名单、SQL 校验和资源限制。

依赖方向：

```text
bi-module-analysis-biz   ──> bi-module-chart-biz ──> bi-infra ──> bi-common
bi-module-dashboard-biz  ──> bi-module-chart-biz
bi-module-report-biz     ──> bi-module-chart-biz
bi-module-chart-biz      ──> bi-module-user-biz（当前用户/角色校验）
```

`bi-web` 显式装配并依赖需要启动的业务模块。`bi-module-chart-biz` 可依赖 `bi-common`、`bi-infra` 和用户模块的公开用户/权限接口；禁止 `bi-infra` 反向依赖任何业务模块，禁止 `chart-biz` 依赖 `analysis-biz` 或 `AiChatClient`，禁止 `analysis-biz` 直接依赖 `Chart` 持久化 Mapper。

Maven 依赖声明要求：`chart-biz` 显式依赖 `bi-common`、`bi-infra` 和 `bi-module-user-biz`；`analysis-biz` 显式依赖 `chart-biz`；`bi-web` 显式装配 chart-biz。依赖方向不得出现 `bi-infra -> chart-biz` 或 `chart-biz -> analysis-biz`。

图表 CRUD 的 `ChartAddRequest`、`ChartEditRequest`、`ChartUpdateRequest`、`ChartQueryRequest` 归 `chart-biz`；`GenChartByAiRequest` 和只用于 AI 分析接口的 `BiResponse` 归 `analysis-biz`。迁移时保留 Java 包名和 HTTP JSON 字段，减少调用方修改。`bi-common` 只保留真正跨业务的基础响应、异常和通用类型，不再放置 Chart 实体和专属 DTO。

## 8. Controller 拆分与迁移

现有 `ChartController` 拆为：

- chart-biz：图表 CRUD、查询和权限相关接口；
- analysis-biz：AI 分析、异步分析和重试接口。

Java Controller 类可以变化，但 HTTP 路径必须保持兼容。迁移期间不能同时注册同一路径的两个 Controller。

迁移分四个可独立编译的阶段：

1. **建立模块契约**：在 `chart-biz` 创建应用服务接口、命令/结果 DTO 和状态模型；补齐 Maven 依赖；
2. **迁移图表持久化**：将 `Chart`、`ChartMapper`、Mapper XML 和 Chart 专属 CRUD DTO 移入 `chart-biz`；将 `GenChartByAiRequest`、`BiResponse` 移入 `analysis-biz`；`bi-infra` 保留通用 MyBatis 配置；
3. **迁移业务编排与接口**：将旧 `ChartService` 留作 chart-biz 内部 CRUD 服务，新增 `ChartApplicationService` 受控任务契约和实现，并迁移图表 CRUD Controller；analysis-biz 改为依赖该契约；AI 分析与重试 Controller、MQ Consumer 保留在 analysis-biz；
4. **模块验收**：完成模块编译和单元/组件测试，确认 Mapper XML 位于 chart-biz classpath 约定位置、HTTP 路径没有重复注册；全量 Spring 集成验证留到最终系统整合阶段。

迁移期间保留数据库 `chart` 表及字段，不要求删除或重建数据。历史小写状态通过兼容读取处理；新写入一律使用大写状态。

## 9. 异常和权限边界

- 图表不存在：返回资源不存在错误；
- 非所属用户操作：返回无权限错误；
- 非法 ID、空名称、超长字段：返回参数错误；
- 删除 RUNNING 任务：拒绝操作；
- 重复删除：保持幂等或返回明确业务结果；
- 所有查询默认过滤 `isDelete = 0`；
- 管理员能力必须显式判断；
- 手动创建图表时 `userId` 从当前登录用户写入，不信任请求体提供的用户 ID；
- 普通编辑接口不能覆盖分析产物和任务状态字段；
- 状态流转通过预期旧状态条件更新，失败时返回明确的状态冲突结果；
- `execMessage` 不保存 API Key、Authorization、Bearer Token 等敏感信息。

## 10. 测试和验收标准

chart-biz 模块测试必须覆盖：

- 状态转换；
- 用户归属和管理员权限；
- 运行中任务不可删除；
- 逻辑删除过滤；
- 图表结果和失败信息更新；
- 只允许合法状态转换，重复取得 RUNNING 执行权失败；
- 失败状态的重试准备仅对所属用户和 FAILED 任务成功。

analysis-biz 的模块测试必须 Mock `ChartApplicationService`，覆盖任务创建、领取执行权、成功、失败、重复消息和失败重试，不出现 `ChartMapper` 或 `Chart` 持久化实体的直接调用。

本阶段不新增依赖 MySQL、Redis、RabbitMQ 或真实密钥的 `bi-web` Spring 集成测试。全量 Spring 集成测试留到最终系统整合阶段统一执行；真实智谱 API 验证仍放在 `bi-web`，默认关闭，须通过既有显式系统属性开启。

MVP 完成标准：

1. chart-biz 可以独立编译并通过模块测试；
2. `Chart`、`ChartMapper`、Mapper XML 和 Chart CRUD DTO 都由 chart-biz 持有；
3. `bi-infra` 不再依赖或引用 Chart 领域类型，不产生 Maven 循环依赖；
4. analysis-biz 不再直接依赖 ChartMapper 或随意更新 Chart 实体；
5. 图表 CRUD 与 AI 分析职责分离，原有 `/api/chart/**` 路径和 JSON 合约兼容；
6. 成功、失败、重试及重复执行保护均正确落库；
7. 所有权、管理员权限、软删除和 RUNNING 不可删除规则有效；
8. dashboard、report 后续可以只依赖 chart-biz 获取图表能力。

## 11. 非本版本范围

- 图表版本管理；
- 图表收藏、分享和公开链接；
- 看板布局；
- 报表导出；
- SQL Agent；
- 多图表联动；
- 图表缓存和搜索索引优化。

## 12. V1.0 已定稿的设计决策

以下内容为本版本已确认的实施边界，不再作为待讨论项：

1. `/edit` 和 `/update` 不允许修改 `chartData`；如需替换分析数据，应创建新的分析任务。服务端只从请求中复制白名单字段，忽略图表结果、状态、错误信息和用户归属等字段。
2. `/api/chart/list/page` 仅管理员可调用；普通用户只能通过 `/api/chart/my/list/page` 查询自己的图表。
3. 未使用且通过 `${querySql}` 拼接原始 SQL 的 `queryChartData` 从 Mapper 和 XML 中一并移除，不迁移到 chart-biz；未来 SQL Agent 单独设计安全查询能力。
