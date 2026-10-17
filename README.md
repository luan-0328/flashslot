# FlashSlot 设备预约平台

面向共享实验设备的预约系统，提供设备排期、预约、FIFO 候补、签到、信用管理和异常恢复。采用 **Java 模块化单体 + Vue 3**，预约与时间格占用在同一个 MySQL 事务内完成。

[在线演示](http://114.55.232.26/) · [手机号模拟注册](http://114.55.232.26/#/register) · [后端规则与接口](flashslot/README.md) · [部署说明](deploy/flashslot/README.md)

演示站已开放手机号自主注册：获取验证码后，六位验证码直接显示在页面，输入验证码和自设密码即可注册并自动登录。**不发送真实短信、不验证手机号归属**，无需学号。管理员账号由管理员创建，首个管理员通过一次性 CLI 初始化。

## 已实现功能

| 使用者 | 功能 |
| --- | --- |
| 普通用户 | 手机号注册与密码登录、设备排期、连续时段预约、加入/取消候补、取消/提前结束预约、签到、站内通知、个人信用与限制查询、修改密码 |
| 管理员 | 账号启停与密码重置、设备及单日日历维护、设备暂停/恢复/退役、现场签到码、异常释放任务与消息恢复、信用调整、操作审计 |
| 后台任务 | 提前维护 14 天设备日历、订单到期结算、候补过期处理、取消即派发撮合、漏派补偿、消息重试、有限期历史清理 |

预约以 **30 分钟**为一格，开放今天起 **7 天**的申请窗口；单次最多 4 小时，同一用户每天跨设备合计最多 4 小时，不能跨设备预约重叠时段。取消时没有有效候补便直接释放；有候补才进入 HANDOFF。暂停设备保留候补，但暂停期间不转正。

## 技术如何落地

| 技术 | 实际用途 | 核心代码 |
| --- | --- | --- |
| MySQL / InnoDB | 设备格与用户格唯一约束防双占；行锁、事务统一处理订单、配额与业务事件 | [预约服务](flashslot/src/main/java/com/flashslot/booking/service/impl/BookingServiceImpl.java)、[占用表结构](db/03_waitlist_release_occupation.sql) |
| Spring Security / BCrypt / JWT | 密码认证、角色授权、凭据版本检查和持久化 Token 撤销；停用/改密立即影响旧 Token | [认证模块](flashslot/src/main/java/com/flashslot/auth) |
| Redis / Lua | 登录共享限流；模拟验证码的冷却、TTL、次数控制；带 revision 的公开排期缓存 | [登录限流](flashslot/src/main/java/com/flashslot/auth/service/LoginRateLimiter.java)、[验证码](flashslot/src/main/java/com/flashslot/auth/service/RegistrationCodeService.java)、[排期缓存](flashslot/src/main/java/com/flashslot/resource/service/impl/ScheduleCacheServiceImpl.java) |
| Redisson | 排期缓存重建时合并热点竞争；数据库仍负责最终业务一致性 | [排期缓存实现](flashslot/src/main/java/com/flashslot/resource/service/impl/ScheduleCacheServiceImpl.java) |
| RabbitMQ / Outbox / Inbox | 站内通知、候补撮合指令（取消即触发、数据库补偿兜底）；业务事件同事务落库，发布确认、消费防重、失败落库和管理员恢复 | [消息模块](flashslot/src/main/java/com/flashslot/messaging)、[恢复模块](flashslot/src/main/java/com/flashslot/operations) |
| Flyway | V1–V10 增量迁移与启动校验，当前共 16 张表 | [迁移目录](flashslot/src/main/resources/db/migration) |
| Micrometer / Prometheus | 发布/消费结果及耗时、积压、死信与撮合逾期指标；提供可选抓取与告警规则 | [指标说明](docs/message-metrics.md)、[监控配置](deploy/flashslot/prometheus.yml) |
| Vue 3 / Element Plus / Axios | 用户与管理端按角色导航、幂等键复用、查询乱序隔离、跨上海午夜更新排期日期 | [前端目录](flashslot-web/src) |

请求幂等记录由 MySQL 保存：同一键与同一请求可回放首次业务结果，换参数返回冲突；暂时性 5xx 不固化为七天失败结果。前端遇到网络或 5xx 保留原键，写请求由用户主动重试。

架构、事务边界及已知限制见[设计说明](docs/FlashSlot-开发设计文档.md)。这是单体应用，没有 Nacos、Feign 或独立的用户/订单微服务。

## 仓库结构

```text
flashslot/          Spring Boot 后端、Flyway 迁移及后端测试
flashslot-web/      Vue 前端、依赖锁文件及前端测试
db/                 按编号执行的手工 SQL 与约束验证入口
deploy/flashslot/   Compose、Nginx、RabbitMQ 和可选监控配置
docs/               当前设计、初始化、指标、部署和验收说明
scripts/            本机启停、临时中间件和隔离验证脚本
```

`.run/`、构建产物、依赖目录、日志和 `.env` 已忽略，不提交凭据。数据库常规升级使用 Flyway；`db/` 供独立手工建库和结构验证，不要对已由 Flyway 管理的库重复执行。

## 本机快速体验

Windows 环境准备：PowerShell 7.4+、JDK 21、Maven、Node.js 22.12+、MySQL 8 的 `mysql.exe` / `mysqld.exe`、Redis 及 Erlang。相关程序需要在 PATH，Redis 可通过 `REDIS_SERVER_EXE` 指定路径。首次运行需要下载依赖与独立 RabbitMQ 发行包。

在仓库根目录执行：

```powershell
pwsh -NoProfile -File scripts/start-flashslot-local.ps1
```

- 前端：`http://127.0.0.1:5173`；后端：`http://127.0.0.1:18080`。
- 脚本使用独立的本机 MySQL、Redis 和 RabbitMQ，不修改已有服务。
- `demo-user` / `demo-admin` 的随机密码由终端输出，脚本不把密码写入仓库。
- 体验环境使用 `local,demo`；普通启动不会自动创建演示账号。

结束体验并保留体验数据库：

```powershell
pwsh -NoProfile -File scripts/stop-flashslot-local.ps1
```

单独开发前端、端口配置及会话行为见[前端说明](flashslot-web/README.md)。自行管理中间件时，后端的环境变量及启动方法见[后端说明](flashslot/README.md)。

## 构建与验证

后端打包与前端构建：

```powershell
mvn -f flashslot/pom.xml -DskipTests package
npm --prefix flashslot-web ci
npm --prefix flashslot-web run build
```

`-DskipTests` 仅用于打包，不表示验证通过。包含真实 MySQL、Redis、RabbitMQ 的完整隔离回归从根目录执行：

```powershell
pwsh -NoProfile -File scripts/verify-flashslot-foundation.ps1 -WithRabbit -WithRedis
pwsh -NoProfile -File scripts/verify-flashslot-schema.ps1
npm --prefix flashslot-web test
```

隔离脚本创建自己的回环实例和临时数据目录，结束后清理，不连接已有数据库。直接执行 `mvn test` 时，依赖私有中间件环境变量的集成测试可能跳过，应以上述脚本的完整结果为准。

截至 2026-10-17，最近一次完整回归为后端 **273 项**、前端 **35 项**、数据库结构 **56 项**通过；模拟注册另有 **7 项**专项回归通过。具体覆盖及限制见[验收说明](docs/FlashSlot-本机验收.md)，不将本机并发冒烟描述为生产容量结论。

## 部署与配置

[Compose 部署说明](deploy/flashslot/README.md)包含发布文件、数据卷、代理来源、固定镜像摘要和可选监控。密钥、数据库密码与运行配置由部署目录 `.env` 提供，不能提交到 Git。

| 配置 | 默认行为 |
| --- | --- |
| `APP_PROFILE` | `production`；Swagger 关闭；显式 `local` 才开放开发文档 |
| `JWT_SECRET` | 必须提供至少 32 个随机字节的 Base64，不内置默认密钥 |
| `REGISTRATION_DEVELOPMENT_CODE_ENABLED` | 默认 false；显式 true 开启手机号模拟注册，Compose 已转发此配置 |
| `TRUSTED_PROXIES` | 仅接受配置来源提供的合法 `X-Real-IP`，忽略其他来源的转发头 |
| `METRICS_ALLOWED_NETWORKS` | 默认回环地址；Compose 明确授权私有子网 |

首次部署需要先完成 Flyway 迁移，再使用[管理员初始化 CLI](docs/admin-bootstrap.md)。现有演示站的发布版本、环境差异与最近验收结果见[服务器记录](docs/FlashSlot-服务器联调.md)。

## 当前边界

- 注册是模拟验证码流程，尚未接真实短信或实名验证；签到码也不能证明本人实际到场。
- 自动撮合依赖 RabbitMQ。数据库保留任务与 Outbox，可在恢复后补偿；不保证故障期间立即转正。
- 无订阅的历史事件使用 ARCHIVED，默认保留 90 天；PUBLISHED 与 Inbox 继续保留以维护防重，长期容量治理仍需另行设计。
- Prometheus 配置和规则已校验，演示站已验证私网指标抓取；可选 Prometheus 容器尚未启用，也未接外部告警通知。
- 当前是单机 HTTP 演示部署，尚未完成 HTTPS 接入、生产容量测试及完整备份恢复演练。
