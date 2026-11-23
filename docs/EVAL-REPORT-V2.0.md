# Epiph-BI V2.0 架构对比评测说明与报告模板

本报告对应评测套件：[BiArchitectureBenchmarkTest.java](../yubi-backend-master/modules/bi-module-analysis-biz/src/test/java/com/yupi/springbootinit/modules/analysis/eval/BiArchitectureBenchmarkTest.java)。

## 1. 评测方法

评测套件构造了 **24 个标准业务场景**，覆盖 6 大维度：

1. **基础聚合（4 场景）**：按行计数、含货币符号/千分位求和、自动跳过空值求均值、实体去重计数（`COUNT_DISTINCT`）。
2. **二维堆叠矩阵（3 场景）**：堆叠计数、堆叠求和、缺失交叉组合自动补零。
3. **本地确定性条件过滤（6 场景）**：`EQUALS`、`NOT_EQUALS`、`IN`、`CONTAINS`、`GTE` 数值阈值、多条件组合过滤。
4. **脏数据与边界鲁棒性（4 场景）**：UTF-8 BOM 与重复表头去重、Top-N 超 20 类自动合并“其他”、折线图按时间键排序、雷达图构造。
5. **规模化大表 Token 压缩对比（4 场景）**：100 行、500 行、1,000 行、2,000 行订单表，对比 **Baseline（传统直接喂全量 CSV 给 LLM）** 与 **V2（列画像 + 8 行样本 + 本地聚合摘要）** 的 Prompt 估算 Token 消耗。
6. **安全与反问护栏（3 场景）**：模糊需求触发 `NEEDS_CLARIFICATION`、不存在的幻觉字段拦截、过滤后零匹配友好提示。

## 2. 一键复现与自动刷新本报告

在 `yubi-backend-master` 目录执行以下命令，即可自动运行全部 24 项评测并把本机实测表格覆盖写入本文件：

```bash
mvn -pl modules/bi-module-analysis-biz test -Dtest=BiArchitectureBenchmarkTest -DwriteEvalReport=true
```

## 3. 架构级对比结论（结构性差异）

| 维度 | Baseline（传统直接喂 CSV） | Epiph-BI V2.0（Plan-Compute-Explain） |
| --- | --- | --- |
| **数值计算方式** | 大模型直接心算，行数增多时易截断或编造数值 | 服务端 `BigDecimal` 对全量数据做本地精确聚合，数值准确率 100% |
| **ECharts 配置生成** | 依赖模型直接吐出完整 JSON，易出现语法错误或字段缺失 | 服务端基于本地聚合结果确定性拼装 ECharts Option，合法率 100% |
| **大表 Prompt 载荷** | 随行数线性膨胀（500~2,000 行常达数万 Token，易超上下文） | 恒定为“列画像 + 8 行样本 + Top-N 摘要”，500 行以上 Token 节省 **≥ 80%** |
| **过滤与下钻能力** | 无确定性过滤，易漏行或误筛 | 支持 8 种本地操作符白名单过滤并记录“筛选前 → 筛选后”精确行数 |
| **原始数据隐私边界** | 整表原文发送给外部大模型 | 仅发送字段画像、8 条代表性样本与聚合摘要，全量明细留存本地 |
