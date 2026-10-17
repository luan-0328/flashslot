# FlashSlot 验证说明

记录日期：2026-10-17。仅保留当前实现的验证证据；服务器状态为最近部署时的快照，不代表持续健康监控。

## 最近结果

| 验证 | 最近结果 | 本机日志/报告 |
| --- | --- | --- |
| 完整后端回归：私有 MySQL、RabbitMQ、Redis | 273 项，0 失败、0 错误、0 跳过，打包成功 | `.run/resume-fix-full.log` |
| 独立 SQL 结构与约束检查 | 56 项通过 | `.run/governance-schema.log` |
| 前端测试与生产构建 | 35 项通过、构建成功 | `.run/resume-ui-final-test.log`、`.run/resume-ui-final-build.log` |
| 手机号模拟注册专项回归 | 7 项通过、构建成功 | `.run/phone-registration-final.log` |
| 模拟注册启用后的前端回归 | 35 项通过 | `.run/phone-registration-web.log` |
| Prometheus 配置和告警规则 | 官方 promtool strict lint 通过，5 条规则 | 本机临时校验工具与配置位于 `.run/prometheus-config-check/` |
| 公网部署与模拟注册 | 构建哈希、接口、迁移、真实注册/登录链路通过 | `.run/flashslot-server-20261017-verification.json`、`.run/phone-registration-server-verification.json` |

`.run/` 是忽略的本机目录，不随仓库交付。新克隆可通过下面的命令重新生成自己的测试结果；不要把不存在的本机日志当作仓库附件。

## 重现验证

Windows 需要 PowerShell 7.4+、JDK 21、Maven、Node.js 22.12+、MySQL 8 的 mysql/mysqld、Redis、Erlang。Redis 可通过 REDIS_SERVER_EXE 指定；RabbitMQ helper 下载独立发行包。依赖准备与启动见[根 README](../README.md)。

```powershell
pwsh -NoProfile -File scripts/verify-flashslot-foundation.ps1 -WithRabbit -WithRedis
pwsh -NoProfile -File scripts/verify-flashslot-schema.ps1
npm --prefix flashslot-web ci
npm --prefix flashslot-web test
npm --prefix flashslot-web run build
pwsh -NoProfile -File scripts/tests/start-local-state.Tests.ps1
```

后端两个验证脚本使用独立临时数据目录、随机回环端口与私有中间件，结束后清理。不要对生产数据库手动设置 FLASHSLOT_PRIVATE_TEST 等私有测试开关。

仅验证注册链路时：

```powershell
pwsh -NoProfile -File scripts/verify-flashslot-foundation.ps1 -WithRedis -Test 'RegistrationIntegrationTest,RegistrationDisabledIntegrationTest'
```

## 已覆盖行为

- 数据库联合唯一约束、多格 INSERT 原子性、账号权限、订单归属、跨设备时间冲突、每日配额、回滚与锁竞争。
- 取消/爽约/提前结束后没有有效候补立即释放；有候补进入 HANDOFF；重复取消与关联错误不能误删其他订单占用。
- 候补 FIFO、到期、PAUSED 不转正、重复撮合防重、业务提交与消费确认之间中断、漏派补偿及异常任务恢复。
- 到期游标扫描跨过失败批次继续处理健康记录，故障修复后可重试。
- JWT 凭据版本与撤销、改密/启停竞争、管理操作审计回滚、可信代理与共享登录限流。
- 模拟验证码冷却/失效/输错上限、注册固定 USER、手机号并发唯一性、自动登录失败时账号回滚、显式关闭注册。
- Outbox 发布确认与租约、Inbox 防重、真实消费失败重试、断开 DLX 时失败原文落库、存储故障重投、死信恢复与上下文隔离。
- ARCHIVED 留痕与有限期清理、消息指标结果与耗时、数据库快照故障、端点真实来源 ACL。
- 前端 Token 失效处理、退出失败保留凭据、幂等键复用、查询乱序、模拟验证码显示、能力关闭时禁止提交、上海午夜日期更新。

服务器验收与环境差异见[部署记录](FlashSlot-服务器联调.md)。注册临时账号已清理，不保存验收用密码或 Token。

## 尚未验证的范围

没有生产容量结论，未做完整备份恢复演练或 HTTPS 接入。可选 Prometheus Compose 组合未启用，告警规则未接外部通知。既有本机小规模并发冒烟与 HTTP 检查不能替代这些验收。

设备恢复回归补充：同一分钟已消费的暂停派发不阻挡恢复新事件，临近截止仍可转正；新旧事件重复消费只有一个订单；派发写入失败时设备状态、任务时间与审计一起回滚，原版本可重试。恢复事件按设备版本与任务身份防重。
