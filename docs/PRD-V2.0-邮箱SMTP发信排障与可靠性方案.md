# PRD：邮箱验证码 SMTP 发信排障与可靠性加固方案（Epiph-BI）

- **文档版本**：V2.0
- **记录日期**：2026-11-23
- **适用范围**：`bi-module-user-biz`、`bi-web`
- **关联凭据配置**：`yubi-backend-master/modules/bi-web/src/main/resources/application-local.yml`（已加入 `.gitignore`，严禁提交真实授权码）

---

## 1. 背景与目标

Epiph-BI 支持通过 QQ 邮箱 SMTPS（`smtp.qq.com:465`）发送 6 位一次性验证码，覆盖**绑定邮箱、邮箱验证码登录、忘记密码重置**三个核心场景，且生产配置强制开启 `app.mail.dev-fallback-on-error: false`（严格真发信，禁止静默降级）。

在 macOS 本地开发联调阶段，曾反复遇到 `smtp.qq.com:465` TLS 握手超时与远端中断问题。本 PRD 系统沉淀已落地的代码级加固方案（`DirectSmtpsClient`）以及最关键的**本机虚拟网卡（代理 TUN 模式）排障经验**，作为项目长期维护与面试复盘的标准技术文档。

---

## 2. 故障现象与日志特征对照

| 场景阶段 | 关键异常日志 | 典型误区 | 实际根因 |
| :--- | :--- | :--- | :--- |
| **使用默认 `jakarta.mail` 时** | `MessagingException: Could not connect to SMTP host: smtp.qq.com, port: 465` -> `SocketTimeoutException: Read timed out`（卡在 `SSLSocketImpl.startHandshake`） | 怀疑 QQ 邮箱授权码过期或端口填错 | 尚未走到 `AUTH LOGIN`（授权码错误会报 `535`），问题出在 `jakarta.mail 1.6.7` 的 `SocketFetcher` TLS 握手及网络劫持 |
| **开启代理 TUN 虚拟网卡时（即使已切 `DirectSmtpsClient`）** | 1. 真实腾讯 IP（如 `120.233.23.191` / `120.241.140.18`）：`SSLHandshakeException - Remote host terminated the handshake`<br>2. Fake-IP（`198.18.0.x`）：`SocketTimeoutException - Read timed out` | 怀疑发信客户端代码或 QQ 服务端故障 | **代理 TUN 虚拟网卡在操作系统路由层接管了全部出站流量**：Fake-IP 无法完成握手；真实 IP 被转到代理节点后因节点封禁 SMTP 465 端口而被直接掐断 |
| **关闭 TUN 虚拟网卡后** | `[DirectSMTPS] TLS handshake OK via ... (TLSv1.3)` -> `[DirectSMTPS] mail accepted by smtp.qq.com:465 -> 250 OK` | — | 直连腾讯 SMTP 服务器成功，验证码秒达 |

---

## 3. 根因深度分析

### 3.1 最核心根因：代理 TUN 虚拟网卡拦截（一关即好）
1. **为何代码里的 `Proxy.NO_PROXY` 挡不住 TUN 模式**：
   - `new Socket(Proxy.NO_PROXY)` 仅能绕过 JVM / 系统设置的 HTTP/SOCKS 应用层代理。
   - Clash / Mihomo / Surge 的 **TUN 模式** 会在 macOS 创建虚拟网卡并接管操作系统 L3 路由表，所有出站 TCP 包都会先进入虚拟网卡。
2. **双重失败链路**：
   - **DNS 污染为 Fake-IP（`198.18.0.0/15`）**：连接 Fake-IP 在 TLS 握手阶段直接读超时（`Read timed out`）。
   - **真实 IP 被代理节点拒连**：绝大多数代理节点为防垃圾邮件封禁了 SMTP 端口（`25 / 465 / 587`），导致连接真实 QQ IP 时远端直接重置握手（`Remote host terminated the handshake`）。
3. **结论**：**关闭代理软件的 TUN 虚拟网卡后，问题立竿见影彻底解决**（可保留普通系统代理，不影响浏览器科学上网）。

### 3.2 框架层根因：Spring Boot 2.7 内置 `jakarta.mail 1.6.7` TLS 握手缺陷
- Epiph-BI 使用 Spring Boot 2.7 + JDK 17，其默认依赖的 `jakarta.mail 1.6.7` `SocketFetcher` 在 JDK 17 下与 QQ 邮箱 465 端口存在 TLS 握手兼容隐患，且默认不会遍历全部 DNS 解析地址（IPv4/IPv6 双栈回退），出错信息也不显示具体命中的 IP。

---

## 4. 已落地方案与核心代码架构

### 4.1 原生 JDK 17 直连 SMTPS 客户端
- 文件：`yubi-backend-master/modules/bi-module-user-biz/src/main/java/com/yupi/springbootinit/service/DirectSmtpsClient.java`
- 设计要点：
  1. **绕开旧版 `jakarta.mail`**：直接使用 JDK 17 原生 `SSLSocketFactory` + `Socket(Proxy.NO_PROXY)` 建立 TLS 连接，完整实现 `EHLO -> AUTH LOGIN -> MAIL FROM -> RCPT TO -> DATA -> QUIT` 协议交互。
  2. **多地址自动回退**：通过 `InetAddress.getAllByName(host)` 遍历全部解析结果，单 IP 失败自动切换下一个 IP（兼容 IPv4/IPv6 双栈）。
  3. **显式 TLS 与 SNI**：锁定 `TLSv1.3` / `TLSv1.2` 并设置 `SNIHostName(host)`，超时控制为连接 `5000ms` / 读写 `10000ms`。
  4. **高可观测性日志**：逐个打印解析地址数量、尝试连接的 IP、失败异常类型及握手成功的 TLS 版本，一眼区分是 Fake-IP（`198.18.x.x`）、节点封禁（`SSLHandshakeException`）还是认证失败（`535`）。

### 4.2 验证码发信服务与严格模式
- 文件：`yubi-backend-master/modules/bi-module-user-biz/src/main/java/com/yupi/springbootinit/service/PasswordResetMailService.java`
- 设计要点：
  - 运行时从 `JavaMailSenderImpl` 读取 `spring.mail.*` 配置构造 `DirectSmtpsClient`；保留双参构造器以兼容单元测试中 Mock `JavaMailSender` 的场景。
  - 配置 `app.mail.dev-fallback-on-error: false`，发信失败直接抛出携带根因摘要的 `BusinessException`，绝不静默伪造成功；对 `535` 认证失败单独给出明确的授权码排查提示。
  - 所有发信日志对邮箱地址做脱敏处理（如 `12***6@qq.com`）。

### 4.3 本地凭据隔离规范
- 文件：`yubi-backend-master/modules/bi-web/src/main/resources/application-local.yml`
- QQ 邮箱账号与 16 位 SMTP 授权码仅存于已被 `.gitignore` 忽略的 `application-local.yml` 或环境变量中，主配置 `application.yml` 仅保留占位符。

---

## 5. 本地开发排障 SOP（快速指南）

> **第一优先级：遇到 QQ 邮箱发信超时或 `Remote host terminated the handshake`，先检查并关闭代理的 TUN 虚拟网卡！**

### 方案 A：一键关闭虚拟网卡（最省心）
1. 打开本机代理客户端（Clash / Mihomo / Surge 等）。
2. 关闭 **TUN 模式（虚拟网卡）**；普通系统代理可以保持开启。
3. 无需重启后端，直接重新触发验证码发送。

### 方案 B：必须开启 TUN 模式时的直连白名单
在代理配置中将 QQ SMTP 域名加入 Fake-IP 绕过与直连规则：
```yaml
dns:
  fake-ip-filter:
    - "smtp.qq.com"
    - "+.qq.com"

rules:
  - DOMAIN,smtp.qq.com,DIRECT
  - DOMAIN-SUFFIX,qq.com,DIRECT
```

### 异常速查表
1. **日志出现 `198.18.x.x` 或 `Remote host terminated the handshake`** -> TUN 虚拟网卡仍在拦截，立即执行方案 A 或方案 B。
2. **日志出现 `535` / `MailAuthenticationException`** -> QQ 邮箱 16 位授权码失效或 `spring.mail.username` 与发件人不匹配，去 QQ 邮箱设置 -> 账号 -> POP3/IMAP/SMTP 重新生成授权码并更新 `application-local.yml`。
3. **如未来开启 Spring Boot Actuator 邮件健康检查** -> 建议配置 `management.health.mail.enabled: false`，避免默认 `MailHealthIndicator` 绕开 `DirectSmtpsClient` 触发无意义的启动告警。

---

## 6. 验收标准

- [x] 关闭代理 TUN 虚拟网卡后，绑定邮箱、邮箱登录、重置密码三大场景均输出 `[DirectSMTPS] TLS handshake OK` 并成功送达 6 位验证码。
- [x] `dev-fallback-on-error: false` 生效，网络异常或授权码错误时明确返回业务失败提示，不误报成功。
- [x] 仓库中无任何明文 QQ 邮箱授权码或私密凭据泄露。
