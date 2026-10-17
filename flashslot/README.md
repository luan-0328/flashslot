# FlashSlot 后端

独立 Maven 工程，Java 21 / Spring Boot 3.5.6，按业务包组织的模块化单体。
在 IDE 中打开本目录的 `pom.xml`；后端使用独立 Maven 构建，不依赖根目录聚合 POM。

## 已实现能力

- Web、Validation、Security/OAuth2 JWT 基础库、MyBatis-Plus 3.5.14、MySQL 驱动。
- Redis、RabbitMQ、Flyway/MySQL 支持、Actuator、Prometheus 指标库。
- springdoc 2.8.13、Lombok、JUnit/Spring Boot Test、Security Test、Testcontainers MySQL。
- 15 个实体、8 个 Flyway 迁移、UTC Instant 类型处理器、可注入的 UTC Clock。
- 设备资源模块已实现查询、新增、配置修改的 Controller / Service / Mapper；新增时生成未来 14 天日历，管理修改记录审计。
- 创建预约已实现幂等、资格检查、日历锁、两类时间格占用和事务 Outbox；取消/提前结束、加入及取消候补、我的候补及空位产生后自动撮合已实现。短时设备签到码、本人签到、每 5 秒爽约/完成与全局候补过期扫描已实现。操作审计、站内通知、RabbitMQ 投递、管理员任务/投递恢复、信用调整和限制解除已实现。设备查询公开。local profile 提供 API 文档。
- 登录使用 Spring Security AuthenticationManager + BCrypt，签发 HS256 JWT；Resource Server 验证 Bearer Token，Session 无状态，业务操作仍核对数据库当前权限。
- 我的订单列表和订单详情已实现：只读查询当前状态、本人数据隔离、管理员详情权限及订单号游标分页。

## 账号、设备状态、死信和历史清理

- `GET /api/v1/admin/users?cursor=0&limit=20&username=...`：管理员分页查询；username 为可选的精确用户名，响应仅含 ID、用户名、角色和启用状态。
- `POST /api/v1/admin/users`：管理员创建账号，字段 username、password、role（USER/ADMIN）、reason。用户名 3–64 位，以字母或数字开头，其余可含下划线、点、连字符；密码至少 8 个字符、至多 72 个 UTF-8 字节。
- `POST /api/v1/me/password`：凭 currentPassword、newPassword 修改本人密码。
- `POST /api/v1/admin/users/{id}/password-reset`：管理员凭 newPassword、reason 重置密码。
- `PATCH /api/v1/admin/users/{id}/status`：enabled、reason；禁止停用当前管理员本人。改密、重置、停用/启用都会增加凭据版本，旧 Token 无法在重新启用后复活。管理变更与审计同事务，不记录密码。
- `POST /api/v1/auth/logout`：撤销当前 Bearer Token，将 SHA-256 指纹与到期时间写入 revoked_token；不会撤销其他设备的登录。只有撤销成功后前端才清除浏览器凭据，网络失败可以重试。每次密码登录使用独立 jti；认证检查凭据版本及撤销记录，业务仍核对当前账号权限。V6 升级前签出的 Token 需重新登录。
- `GET /api/v1/admin/resources`：分页包含 ACTIVE、PAUSED、RETIRED；公开设备列表仍仅展示 ACTIVE。
- `PATCH /api/v1/admin/resources/{id}/status`：status、version、reason；PAUSED 禁止新申请但保留已有订单和候补。RETIRED 为终态；资源排他锁内检查全部未来/进行中订单、未到期候补和有效 HANDOFF，存在冲突返回 409。
- 每日预约按 Asia/Shanghai 业务日期、同一用户跨设备合计最多 8 格；创建预约和候补转正都在用户锁内汇总 charged_slots。候补入列不预扣，转正时不足则以 DAILY_QUOTA_EXCEEDED 拒绝。取消/提前结束只返还尚未开始格，历史已消耗格仍计入配额。升级前已存在的超额预约不自动撤销，但会阻止继续增加当日用量。
- `GET /api/v1/admin/dead-letters`：查看持久保存的消费死信摘要，不暴露原始消息；`POST /api/v1/admin/dead-letters/{id}/replay` 带 Idempotency-Key 和 reason，在同一事务执行原消费、Inbox、通知、恢复状态和审计。格式/版本不支持返回 409 并保留记录，不允许从管理页改写消息内容。DLQ 接收在数据库提交后才 ACK，存储失败退避并重新入队；旧死信队列消息也会被收录。按来源队列与原文 SHA-256 去重并累计接收次数，UNKNOWN 来源兼容旧哈希。
- 历史清理默认每小时运行，一批各删除最多 500 条到期的已完成幂等记录及 Token 撤销记录，每轮最多 20 个短事务；失败留待下轮。`HISTORY_CLEANUP_SCHEDULING_ENABLED=false` 可关闭。ARCHIVED 留痕事件默认保留 90 天并分批清理；保留订单、账号、审计、通知、其他 Outbox 状态、Inbox 和死信，维护追溯与消费防重。

## 目录

```text
src/main/java/com/flashslot/
  FlashSlotApplication.java
  common/config/          Clock、基础安全配置
  common/persistence/     UTC DATETIME 与 Instant 转换
  common/entity/          ApiIdempotency
  auth/entity/            AppUser
  resource/entity/        Resource、ResourceDay
  booking/entity/         BookingOrder、SlotOccupation、UserSlotOccupation
  waitlist/entity/        WaitlistRecord、ReleaseJob
  credit/entity/          CreditLedger
  messaging/entity/       OutboxEvent、ConsumerInbox、Notification
  operations/entity/      AdminAudit
src/main/resources/
  application.yml
  application-local.yml
  db/migration/           V1–V10
src/test/java/            私有 MySQL 集成测试
```

设备资源代码位于 `resource/controller`、`resource/service/impl`、`resource/mapper`，请求和响应在 `resource/dto`、`resource/vo`。
`auth/mapper`、`auth/service` 提供当前账号权限检查；`operations` 提供同事务审计写入及管理员审计查询；`messaging` 提供通知接口、Outbox 投递和通知消费。
booking、resource、waitlist 等模块按业务功能提供 Controller / Service / Mapper。

订单详情、本人分页和分页锚点的完整 SQL 位于 `src/main/resources/mapper/booking/BookingReadMapper.xml`；`BookingReadMapper` 仅保留方法声明与参数绑定，便于直接查看联表条件和动态分页条件。

本次认证与预约代码参照 [sky-take-out](https://github.com/ScholarChen20/sky-take-out) 的调用习惯整理为 `Controller → Service 接口 → service/impl/ServiceImpl → Mapper`。保留按业务模块分包，登录、创建预约和订单查询分别有独立 Service 接口与实现。设备模块沿用已有的接口/实现分层。

当前身份统一通过 `common/context/BaseContext` 获取：

```java
Long userId = BaseContext.getCurrentId();
```

`BaseContext` 只读取 Spring Security 管理的请求上下文，不查询数据库，不另建 ThreadLocal。Spring Security 完成 JWT 校验、建立身份并在请求结束时清理。公开排期查询使用 `getCurrentIdOrNull()`，未登录可返回 null。创建预约去掉了事务前“用户名 → 用户 ID”的账号查询；事务取得设备、日历及用户锁后，仍检查数据库中的启用状态、角色与处罚，保持并发安全。订单查询、管理员写入和排期本人标记也按 Token 中的用户 ID 定位当前账号。

账号存在、启用及正式角色的通用检查统一在 `auth/service/AccountValidator`，只检查调用方已加载的账号，不额外查库或改变锁顺序；处罚、预约截止和订单归属仍属于各业务规则，后台爽约结算不受账号停用影响。

业务直接放在 `ServiceImpl`，写入口使用 `@Transactional`；Controller 调用 Service 后返回 `Result.success(VO)`，失败由 Service 抛出业务异常，`GlobalExceptionHandler` 统一返回 `Result.error(errorCode,msg)`。不再使用 `TransactionTemplate`、专门的 TransactionService、Outcome 包装或业务回调。通用幂等服务仅提供准备记录、回放和保存结果的公共方法，参与调用方事务。

创建预约、取消和候补的校验拒绝需要保存首次结果，迟到签到需要提交爽约状态，这些明确边界使用 `CommittedBusinessException` 配合 `noRollbackFor`；其他异常仍整体回滚。后台扫描逐条调用已有的订单生命周期服务和候补撮合服务，单笔使用独立事务，防止单条失败影响其他记录。

不再在 Service 内立即循环重试锁冲突。HTTP 请求遇到死锁或锁超时时先完整回滚，返回 503 和 `Result.error`；客户端使用原幂等键重试。订单到期任务在下个 5 秒扫描重试，候补撮合保存失败后按 1、5、15、30 分钟退避重试。

## 登录与身份认证

普通用户手机号注册（本地联调）：

- `POST /api/v1/auth/registration/code`，请求 `phone`（11位中国大陆手机号），返回 `developmentCode`（保留前导零的6位字符串）、`expiresIn=300`、`retryAfter=60`、`mode=DEVELOPMENT`。验证码直接展示前端，不发送短信、不证明手机号实际归属，响应禁止缓存。
- `POST /api/v1/auth/register`，请求 `phone、code、password`；密码至少8个字符、至多72个UTF-8字节。成功 HTTP201，返回与登录相同的 LoginView，直接进入登录态。手机号作为登录用户名，固定创建 USER，不接受客户端 role。后续用手机号和密码登录；已有管理创建的用户名账号仍可登录。
- Redis 保存验证码指纹及5分钟TTL，同号60秒冷却、每24小时10次、来源IP每小时60次，Lua原子校验，输错5次失效；Redis不可用拒绝注册。手机号和用户名双重查重，数据库手机号唯一索引兜底并发。事务失败保留验证码供重试，数据库提交后再清理；清理暂时失败也不能再次创建同一手机号。
- V8 为 app_user 增加可空且唯一的 phone，旧账号与管理员数据保持不变。注册需要独立Redis，即使排期缓存关闭也需要Redis。
- 仅 local 配置默认开启联调验证码；其他配置默认关闭。`REGISTRATION_DEVELOPMENT_CODE_ENABLED=false` 可明确禁用，禁用时两个接口返回503，不返回验证码。当前没有真实短信服务商配置，不应将此模式当成真实手机号认证。 当前演示站按用户要求显式启用 `REGISTRATION_DEVELOPMENT_CODE_ENABLED=true`，Compose 已转发该配置；验证码返回前端用于模拟注册，production profile 和 Swagger 关闭规则保持不变。

应用未设置 `APP_PROFILE` 时默认使用 `production`，不开放联调验证码和 Swagger。需要开发工具时显式设置 `APP_PROFILE=local`；本机启动和隔离验收脚本已显式选择 local，不依赖不安全的默认值。

`GET /api/v1/me/profile` 查询本人最新账号状态，需要 Bearer Token；返回 `id、username、role、creditScore、restrictionUntil、restricted、serverTime`，不接受请求参数指定他人身份，不返回密码。停用或非正式账号拒绝访问，响应禁止缓存。`restricted` 按服务器当前时间计算，到达限制截止时刻即为 false；信用分较低不等于永久禁止预约。

## 日历持续维护

应用就绪时补齐未退役设备的上海今天起 14 天日历，此后每分钟复查；停机多日后自动补齐当前窗口中的所有缺口。逐设备独立事务，按设备锁串行化补齐和默认配置修改，只新增缺失行，不覆盖已有开放时间、关闭状态、revision 或历史记录。新日历复制当前设备默认开放时间，PAUSED 设备保留维护，RETIRED 跳过。单设备失败不影响其他设备，下一轮重试。可用 `RESOURCE_CALENDAR_SCHEDULING_ENABLED=false` 关闭自动维护；隔离测试脚本显式关闭后台扫描并直接验证业务。

维护 14 天不改变申请规则：用户仍只能申请今天起 7 天内的预约和候补。默认配置修改只影响以后新生成的日历。

### 登录接口

`POST /api/v1/auth/login`，Content-Type 为 `application/json`：

```json
{"username":"student_a","password":"填写账号密码"}
```

成功 HTTP 200，返回 `Result<LoginView>`，data 包含 `accessToken、tokenType=Bearer、expiresAt、user`；user 仅包含数据库规范用户名 username 和登录时角色 role。默认有效期 2 小时，可通过 JWT_TTL_SECONDS 设置为 60–86400 秒。响应带 `Cache-Control: no-store`，密码、哈希和 Token 不写入业务事件、数据库或应用日志。

后续访问受保护接口使用请求头：

```text
Authorization: Bearer <accessToken>
```

- 密码由 Spring Security 的 DaoAuthenticationProvider / BCryptPasswordEncoder 校验，JWT 由 JwtEncoder 签发、Resource Server 验证，不手写密码比较或 JWT 过滤器。用户名最长 64 字符，密码为非空且最多 72 个 UTF-8 字节，不自动裁剪密码。
- JWT 固定 HS256，必须匹配 issuer=flashslot、audience=flashslot-api，必须有 sub、userId、iat、exp。userId 为正整数且来自密码认证后的账号，不接受请求体指定；签发时间不能在未来，恰好达到 exp 即失效。sub 保留签发时的数据库规范用户名，业务身份以不可变用户 ID 为准，账号改名不改变旧 Token 的归属；roles 映射为 ROLE_USER/ROLE_ADMIN。此前不带 userId 的旧 Token 不再接受，需要重新登录。
- 只接受 Authorization Header 中的 Bearer Token；不从 Cookie、URL 参数或 Session 取认证身份，不开启表单登录或 HTTP Basic。登录和 Bearer 写请求不要求 CSRF Token；其他写请求仍保留 CSRF 检查。
- 普通用户和管理员可以登录；未知账号、错误密码、禁用账号及 DEMO 都返回相同的 401 INVALID_CREDENTIALS。无效/过期 Token 返回 401 UNAUTHENTICATED。数据库认证故障返回 503 AUTHENTICATION_UNAVAILABLE，不伪装成密码错误。
- 登录限流使用 Redis Lua 原子计数与 TTL：默认每 IP 每 60 秒最多 30 次、每账号最多 5 次，成功和失败都计数。同一 Redis 与键空间下各实例共享额度，应用重启不会清空 Redis 窗口。窗口从首次请求开始，拒绝不续期；配置为 `LOGIN_IP_LIMIT`、`LOGIN_ACCOUNT_LIMIT`、`LOGIN_WINDOW_SECONDS`，各实例应保持一致。先限制来源 IP，再按数据库账号 ID 限制账号，避免大小写/重音等价用户名绕过；未知账号按规范化输入计数。超限返回 429，附 Retry-After。Redis key 中身份使用 SHA-256 摘要。
- Redis 故障时退回本机影子计数（最多 10000 个桶，过期清理、容量满时暂拒），不会因刚切换降级就丢掉本实例近期尝试；每 2 秒尝试恢复 Redis 并记录降级日志。故障期间各实例独立计数，无法保证全局额度；恢复时也不将本地计数合并回 Redis。Redis 重启丢数据或内存淘汰也可能重置共享窗口，此机制不提供跨故障严格配额。
- 客户端 IP 默认取连接来源，忽略转发头。仅 `TRUSTED_PROXIES` 配置的代理 IP/CIDR（逗号分隔）可提供单个合法 `X-Real-IP`，不接受主机名、地址链或重复头；Nginx 必须覆盖该头。该解析同时用于登录与注册验证码 IP 防刷。Compose 已固定入口 IP 并仅信任它；不要把所有来源设为可信代理，或再无条件启用框架转发头解析。
- IP 使用服务端 remoteAddr，不直接相信客户端 X-Forwarded-For。以后接反向代理时应配置可信代理来源，否则同一代理的请求共享 IP 窗口。
- Token 不是永久权限：预约、订单查询和管理接口仍检查数据库当前账号启用状态及角色，停用/降权后相关业务请求拒绝。提供登出撤销与密码修改接口；没有刷新 Token 接口，到期后重新登录。

普通用户可通过手机号验证码注册，注册成功直接登录；管理员账号仍通过管理员账号管理入口创建，初始管理员通过[一次性 CLI](../docs/admin-bootstrap.md)初始化，不提供公开管理员注册。`app_user.password_hash` 必须保存 `BCryptPasswordEncoder(12).encode(密码)` 生成的 BCrypt 哈希，不能保存明文、MD5 或测试夹具中的占位字符串；角色为 USER 或 ADMIN。不要将初始化密码写入迁移或仓库。

启动必须提供 `JWT_SECRET`：至少 32 个随机字节的标准 Base64 编码，不配置或格式/长度不合法会启动失败。PowerShell 7 可在首次准备环境时生成：

```powershell
$env:JWT_SECRET = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
```

为同一部署安全保存并复用该值；更换密钥会使旧 Token 立即失效。不要在日志、截图或提交内容中暴露密钥。隔离测试脚本会为每次测试生成临时密钥，退出时恢复原环境。

## 设备资源接口

| 方法 | 地址 | 行为 |
| --- | --- | --- |
| GET | `/api/v1/resources?cursor=0&limit=20` | 公开查询 ACTIVE 设备，ID 升序，limit 1–100 |
| GET | `/api/v1/resources/{id}` | 公开查询详情，版本位于 data.version |
| POST | `/api/v1/admin/resources` | ADMIN 新增设备及从上海今天起 14 天日历，直接返回 Result，HTTP 状态为 201 |
| PATCH | `/api/v1/admin/resources/{id}` | ADMIN 局部修改配置，请求 DTO 必须包含非负整数 version |

所有业务接口返回 `Result<T>`：`code、msg、data、errorCode`，`code=1` 成功、`code=0` 失败，失败原因放在 `msg`，机器可读错误码放在 `errorCode`（例如 CHECKIN_EXPIRED、IDEMPOTENCY_CONFLICT）；成功时 errorCode 为 null。HTTP 状态码保留 201/400/401/403/404/409/422/429/500/503 等语义，与 JSON 中的业务成功标记分别使用。安全过滤器和全局异常处理也返回相同结构。Actuator/OpenAPI 等框架端点保留标准协议格式。前端请求封装将错误码保留为 `error.errorCode`，并保留原始 response。

本文中的 `RESOURCE_CLOSED`、`CHECKIN_EXPIRED` 等名称用于说明内部业务异常和幂等记录，不再作为 JSON 的 code 返回。旧幂等记录仍可回放：读取旧的 message 或新的 msg，统一通过当前 Result 格式返回。
列表的 `data` 包含 `items、nextCursor、hasMore`；最后一页 nextCursor 为 null。
详情和新增/修改的 `data` 为 ResourceView，包含 id、name、location、status、defaultOpenStartSlot、defaultOpenEndSlot、version、createdAt、updatedAt。

```json
{"code":1,"msg":null,"data":{"items":[],"nextCursor":null,"hasMore":false}}
```

新增请求示例：

```json
{"name":"3D打印机01","location":"实验楼A201","defaultOpenStartSlot":16,"defaultOpenEndSlot":44,"reason":"新增实验设备"}
```

修改请求示例（version 放在 JSON 请求体中）：

```json
{"version":0,"name":"3D打印机02","reason":"修正设备名称"}
```

Controller 和全局异常处理直接返回 `Result<T>`，不使用 ResponseEntity。设备版本通过响应的 data.version 返回，再通过修改 DTO 的 version 传入，不使用 ETag/If-Match 或字符串版本解析。
PATCH 的 version 必填；可修改名称、位置、默认开放范围，至少提供一个配置字段；配置字段的 null 表示不修改。开放范围满足 `0 <= start < end <= 48`。
修改默认开放范围不会覆盖已生成日历。此接口拒绝 status 等未知字段，暂停/恢复/退役使用独立的设备状态接口；单日日历调整使用下述日历接口，不提供物理删除。
错误返回 `{ "code": 0, "msg": "...", "data": null }`，参数错误 400、未认证 401、无权限/禁用 403、设备不存在 404、版本冲突 409。

管理接口要求 Bearer Token 中的 ADMIN 权限，并在业务事务中按当前用户 ID 检查数据库启用状态及 ADMIN 角色。既有业务测试显式注入带 ID 的身份，登录联调测试使用真实签发的 Token。
审计 reason 必填，操作人取自认证上下文，不接收客户端传入的 actor_id。资源、日历、审计属于同一个事务，权限或审计失败整体回滚。
当前新增资源没有实现请求幂等键回放，不应在响应不确定时盲目重复提交；创建预约使用下述幂等机制。

## 创建预约

`POST /api/v1/bookings`，请求头 `Idempotency-Key` 必填，为 1–64 位字母、数字或 `._:-`。同一次预约意图重试使用原键。使用 Bearer Token 鉴权，用户 ID 直接通过 BaseContext 取得，不接收请求体指定的 userId。

```json
{"resourceId":1,"bookDate":"2026-10-16","startSlot":18,"endSlot":22,"purpose":"EXPERIMENT"}
```

- `bookDate` 为上海今天至今天 + 6 天，区间 `[startSlot,endSlot)`，每格半小时，连续 1–8 格。用途为 `EXPERIMENT`、`STUDY` 或 `DEVICE_TEST`。每天跨设备累计最多 8 格（4 小时），创建和候补转正均在用户行锁内检查；取消只返还尚未开始格。
- 成功 HTTP 201，返回 `Result<BookingView>`：orderNo、resourceId、bookDate、startSlot、endSlot、purpose、status=CONFIRMED、source=DIRECT、chargedSlots、startAt、endAt、checkInOpenAt、checkInDeadline。绝对时间为 UTC；签到窗口为开始前 10 分钟至开始后 15 分钟。
- 必须为当前仍启用的 USER 账号；ADMIN 仅用于管理，不能创建预约、加入候补或本人签到；DEMO 不允许预约。处罚截止前拒绝，到期后即使分数低于 60 也允许。设备必须 ACTIVE，日历已生成、启用且完整覆盖区间。判定时间在业务锁全部取得后捕获，须严格早于开始前 30 分钟。
- 锁序为幂等记录 → 设备共享锁 → 当日日历排他锁 → 本人同区间候补 → 用户排他锁。设备与用户占用都先检查，再用普通批量 INSERT；设备占用及用户占用联合主键是最终防线。冲突或异常不会留下订单或部分占用。本人同设备同日同区间 WAITING 候补成功后撤销。
- 订单、两类占用、日历 revision+1、BOOKING_CREATED / SCHEDULE_CHANGED Outbox 和幂等结果同事务。业务事务只保存事件；通知事件由后台投递 MQ，SCHEDULE_CHANGED 仍等待缓存模块接入。
- 幂等作用域为用户 + CREATE_BOOKING + 键；哈希规范化设备、日期、区间、用途。同键同参数返回首次 HTTP 状态与响应快照，订单后来变化不改变回放；换参数返回 409 IDEMPOTENCY_CONFLICT。回放仍校验账号启用状态。记录保存 7 天归档期限，每小时分批清理已到期且已完成的记录；保留期内不会在原行上覆盖结果，到期清理后同键可作为新请求处理。
- 锁内确定性业务拒绝保存首次结果（例如 403/404/409），相同键重试继续回放；修改意图或条件变化后重新申请使用新键。暂时性 5xx（包括 CALENDAR_NOT_READY）不保存幂等结果，完整回滚后可用原键重试，前端遇到 5xx 也保留原键。旧版本已经保存的 CREATE_BOOKING/CREATE_WAITLIST 日历未就绪 503 会在请求哈希核对后清除并重新评估；不会清除成功结果或绕过换参数冲突。参数/认证失败不保留结果；唯一键最终兜底冲突与基础设施异常回滚，原键可重试。死锁/锁超时返回 503 OPERATION_BUSY。
- 主要错误：400 INVALID_ARGUMENT / INVALID_DATE；401 UNAUTHENTICATED；403 ACCOUNT_DISABLED / BOOKING_RESTRICTED / FORBIDDEN；404 RESOURCE_NOT_FOUND；409 RESOURCE_CLOSED / BOOKING_CUTOFF / RESOURCE_SLOT_CONFLICT / USER_SLOT_CONFLICT / SLOT_CONFLICT / IDEMPOTENCY_CONFLICT；503 CALENDAR_NOT_READY / BOOKING_BUSY。

运行 `pwsh -NoProfile -File scripts/verify-flashslot-foundation.ps1` 在随机本机端口的临时 MySQL 中执行全部集成测试；脚本退出时关闭并清理私有实例，不连接已有数据库。

## 我的订单与订单详情

| 方法 | 地址 | 行为 |
| --- | --- | --- |
| GET | `/api/v1/me/bookings?limit=20` | 当前登录用户的订单，按创建时间倒序，同毫秒按内部 ID 倒序 |
| GET | `/api/v1/bookings/{orderNo}` | 本人或有效管理员查看订单当前状态及释放进度 |

列表响应为 `Result<BookingPage>`，data 包含 `items、nextCursor、hasMore`。每页默认 20 条，limit 范围为 1–100；不返回总数。首次请求省略 cursor 或传空字符串。后续将返回的 nextCursor 原样作为 cursor 传入，例如：

```text
GET /api/v1/me/bookings?limit=20&cursor=BK_上一页返回的订单号
```

cursor 使用上一页末条订单的 orderNo，服务端在本人订单中解析创建时间及 ID，按 `(created_at,id)` 继续向前翻页，避免相同创建时间的记录遗漏或重复。翻页期间新创建的订单在刷新第一页后出现。末页 nextCursor=null、hasMore=false；没有订单时 items=[]。不存在或不属于本人的游标统一返回 400 INVALID_CURSOR。

列表每项和详情 data 均为 `BookingDetailView`，包含创建响应 BookingView 的字段，并增加：

- `resourceName、resourceLocation`：设备当前名称和位置。
- `checkedInAt、terminatedAt、terminationReason、reasonText`：实际签到和终止信息，无值时为 null。
- `version、createdAt、updatedAt`：订单当前版本及记录时间。
- `releaseJobNo、releaseStatus`：本订单取消后触发的释放任务进度；无任务时为 null。状态可为 PENDING/MATCHED/RELEASED/MANUAL_REVIEW。通过 `source_booking_id` 关联，不把候补转正来源任务当成本订单的释放进度。

两个接口都要求登录并检查数据库当前账号仍启用，DEMO 账号不能访问正式订单。信用处罚不妨碍查询已有订单；设备暂停/退役也不会隐藏历史订单。我的订单列表仅向 USER 开放且始终限定当前账号；管理员可通过详情接口查询他人订单，跨用户访问须同时具备认证上下文 ROLE_ADMIN 和数据库当前 ADMIN 角色。无权查看与不存在的订单统一返回 404 BOOKING_NOT_FOUND，不泄露他人订单是否存在。未登录 401，停用账号 403 ACCOUNT_DISABLED，其他无效账号 403 FORBIDDEN。

查询以一个只读一致快照读取账号、游标、订单和释放进度，不加写锁，不推进订单状态。返回数据库已持久化的当前状态，与创建接口的首次幂等响应快照不同。取消路径会归一化已到期的爽约/完成状态，签到接口和后台扫描按各自业务入口推进状态，单纯超过时间不会在只读查询中自动改变状态。响应不返回内部用户 ID、订单 ID、关联数据库 ID 或任务内部错误日志。

## 取消预约与候补（本批三个接口）

| 方法 | 地址 | 作用 |
| --- | --- | --- |
| POST | `/api/v1/bookings/{orderNo}/cancel` | 取消本人订单或提前结束；有效管理员可填写原因取消他人订单 |
| POST | `/api/v1/waitlists` | 为已占用或 HANDOFF 的连续区间申请候补 |
| GET | `/api/v1/me/waitlists?limit=20` | 分页查看本人候补、状态及近似队列位置 |

三个接口都要求 Bearer 登录身份。两个 POST 必须带 `Idempotency-Key`；各自使用 CANCEL_BOOKING / CREATE_WAITLIST 操作类型，URL 中订单号与请求参数纳入哈希。相同键同参数回放首次 HTTP 状态和完整 Result；换参数返回 409 IDEMPOTENCY_CONFLICT。明确的业务拒绝也保存结果，改变意图时使用新键；事务异常回滚后可复用原键重试。回放仍检查账号当前有效性；管理员取消结果还检查当前跨用户权限，降权后不能读取缓存的他人订单。

取消请求体：

```json
{}
```

管理员取消他人订单：

```json
{"reason":"设备维护"}
```

取消成功 HTTP 200，data 为 BookingDetailView，包含当前状态、chargedSlots、terminationReason、releaseJobNo、releaseStatus 等信息。管理员跨用户取消必须同时满足 Token ROLE_ADMIN、数据库当前 ADMIN，并填写非空且最多 256 字的原因，同事务写入 BOOKING_CANCEL 审计。其他用户访问与不存在订单均返回 404 BOOKING_NOT_FOUND。账号预约处罚不阻止本人取消；停用账号仍拒绝。

- 锁序：幂等 → 资源共享 → 日历排他 → 订单 → 操作者/订单用户按 ID 升序 → 占用/账本 → 事件/审计。
- CONFIRMED 在签到截止前取消，变为 CANCELED/USER_CANCEL；CHECKED_IN 且未结束时为 CANCELED/EARLY_FINISH。管理员取消他人订单使用 ADMIN_CANCEL。
- 只处理每格开始时刻大于等于锁后 decisionTime 的未开始后缀；精确边界时该格可释放，超过边界即保留。原订单与已开始格保留，chargedSlots 扣减释放格数，用户锁内汇总用于每日跨设备 8 格配额。
- 持有日历锁时检查能够承接该区间/后缀的有效 WAITING 候补：有候补时未来设备格从 BOOKED 改为 HANDOFF，并关联 PENDING release_job；没有时按精确 booking_id 当场删除未来设备占用，保留 RELEASED 释放记录，立即恢复直约，不经过 MQ。已过期及结束格不一致的候补不阻塞释放。对应用户格按精确订单归属删除。数量不符立即整体回滚，不能误释放他人占用。
- CANCELED/NO_SHOW/COMPLETED 重复取消直接返回当前结果，不重复创建任务、退款或扣分。CONFIRMED 恰好到签到截止时先归一化 NO_SHOW；CHECKED_IN 恰好到结束时先归一化 COMPLETED，不能伪装成普通取消。
- 取消触发的 NO_SHOW 同事务记录唯一信用账本，最多扣 10 分且不低于 0；扣分后低于 60 时，restriction_until 取原值与 decisionTime+7 天的较晚值。签到、取消和独立爽约/完成扫描器共用状态转换逻辑。
- 订单、释放任务、设备/用户占用、信用、日历 revision、Outbox、管理员审计和幂等结果同事务；不调用 Redis/MQ。事件失败全部回滚。

候补请求与创建预约相同：

```json
{"resourceId":1,"bookDate":"2026-10-16","startSlot":18,"endSlot":22,"purpose":"EXPERIMENT"}
```

- 日期为上海今天至今天+6，连续 1–8 格，严格早于开始前 30 分钟申请。设备须 ACTIVE，日历已生成且覆盖区间，账号启用且处罚到期，不能与本人已有预约重叠。
- 目标完整区间中至少有一格 BOOKED/HANDOFF 才需要候补；完全空闲返回 409 WAITLIST_NOT_NEEDED。同人同设备同日期同完整区间最多一条 WAITING，否则 WAITLIST_DUPLICATE；每用户每天最多 3 条未过期 WAITING，跨设备共享上限，超限 WAITLIST_LIMIT。
- 候补不写设备格或用户格，不预扣时长。转正截止 expireAt=开始前 10 分钟；队列按同设备、日期、完整区间的数据库 ID 升序 FIFO，时间不同或部分重叠属于不同队列。
- 成功 HTTP 201，data 为 WaitlistView：waitlistNo、resourceId、resourceName、resourceLocation、bookDate、startSlot、endSlot、purpose、status、reason、expireAt、createdAt、updatedAt、matchedOrderNo、queuePosition。queuePosition 是当前未过期 WAITING 的近似位置，从 1 开始，不承诺一定转正；非有效排队记录为 null。幂等回放保留首次位置，列表读取当前位置。
- GET 返回 `Result<WaitlistPage>`，items、nextCursor、hasMore；默认 20 条，最多 100。nextCursor 为末条候补编号，下页原样传 cursor，按创建时间和 ID 倒序稳定翻页，末页 null。只读本人记录，管理员不使用个人候补功能；别人的游标与未知游标统一返回 400 INVALID_CURSOR。不暴露排在前面的其他用户身份。

**自动撮合由 MQ 事件立即触发，数据库补偿兜底。** 取消事务创建 PENDING 任务时，同事务写入 `RELEASE_DISPATCHED` Outbox，发布器秒级投递到独立 `flashslot.release` 队列，消费者执行撮合；取消请求不同步等待撮合。`release_job` 是事实来源：每分钟补偿扫描为已到期、且没有在途（NEW/SENDING）或两分钟内派发事件的 PENDING 任务补派，覆盖重启、消息丢失、失败退避到期和预算耗尽。同一任务同一分钟使用固定事件 ID，多实例重复扫描只生成一条；broker 故障期间在途事件不会被重复堆积。

A 取消时若存在有效的可承接候补则进入 HANDOFF，否则立即 RELEASED 并恢复直约。消费者按完整区间 FIFO 重新检查候补用户资格、每日配额和时间冲突；合格者创建正式预约，候补和任务变为 MATCHED。PAUSED 设备不生成转正订单，已有候补和交接保留，恢复 ACTIVE 后同事务生成新唤醒事件继续撮合；到期仍按截止规则清理。无人符合时变为 RELEASED 并恢复直约。每个候选使用独立短事务，订单、两类占用、候补状态、任务和业务 Outbox 同事务提交。`release-v1` Inbox 过滤已完成消息；业务提交后但 Inbox/ACK 前崩溃，由任务终态、候补状态、时间格精确归属与订单唯一约束防止重复转正或释放。消息不承诺只投递一次。

设备从 PAUSED 恢复 ACTIVE 时，将待处理任务改为到期，并同事务生成独立的恢复派发事件。事件身份包含设备版本与任务 ID，不受旧派发的两分钟冷却或同一分钟 Inbox 影响；重复消费仍由任务终态、锁和唯一约束防重。

每批最多派发 50 个任务，每次消费最多处理 20 步；预算耗尽保留 PENDING 和 HANDOFF，由补偿扫描两分钟后补派继续。消费异常先回滚，MQ 最多尝试 3 次，耗尽后才记录一次业务失败，并将原文提交 dead_letter 后 ACK，保存失败则重新入队；任务按 1、5、15、30 分钟退避，到期后由数据库补偿扫描重新派发。归属/完整性异常或累计 5 轮失败进入 MANUAL_REVIEW，保留占用，管理员核对后重试或人工释放。重复失败不能在同一退避周期叠加次数。关闭 `RELEASE_SCHEDULING_ENABLED` 只停止补偿扫描；任务创建时的立即派发和已入队消息消费不受影响。

撮合使用 MQ 的理由是事件驱动：取消后秒级触发、取消请求与撮合解耦、失败有统一重试与死信，且无需高频轮询数据库。数据库补偿扫描只负责重新派发，不直接执行撮合，因此 broker 不可用时已有 HANDOFF 的自动撮合会等待恢复；无人排队的取消释放在本地事务完成，不受影响。

撮合死信持久化到现有管理页；恢复操作将原 Outbox 重置 NEW，与死信恢复状态、幂等和审计同事务提交，再由发布器发送，不在管理请求中同步撮合。任务仍遵守到期时间和人工检查状态。恢复后若再次失败，同一死信重新成为 FAILED，允许用新的恢复请求处理。broker 暂不可用时事件留在 Outbox；进程重启、漏扫描或批次积压由补偿恢复。该链路需要启用 RabbitMQ 发布和消费。

正常情况下撮合在取消后数秒内开始，积压或故障会增加等待；设备 PAUSED 期间任务每 5 分钟复查一次，恢复 ACTIVE 时立即到期。达到有效区间开始前 10 分钟时不再转正，而是处理匹配候补的过期状态并释放占用。迟到任务只处理尚未开始格，不自动把原候补改约到其他区间。候补取消及全局过期扫描已实现。

## 操作审计与站内通知

本批新增三个接口，均返回苍穹风格 `Result<T>`（`code、msg、data`）：

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | `/api/v1/admin/audits` | 管理员分页查询操作审计 |
| GET | `/api/v1/me/notifications` | 当前用户分页查询自己的通知 |
| POST | `/api/v1/me/notifications/{id}/read` | 将本人通知标为已读，重复调用保留首次时间 |

审计查询要求 Token 和数据库当前角色都为启用的 ADMIN。支持 `actorId、operation、targetId、from、to、cursor、limit`；时间使用带时区的 ISO-8601，范围两端包含，起始不能晚于结束。默认每页 20 条，最大 100；按 createdAt、id 倒序，响应 `items、nextCursor、hasMore`。每项包含操作人 ID/用户名、操作、目标、原因、追踪编号（未设置时为 null）和时间，不返回账号敏感字段。审计由原业务事务写入，不开放客户端伪造或修改日志的接口。

通知查询支持 `unreadOnly=true、cursor、limit`，默认每页 20 条，最大 100。响应包含 `items、nextCursor、hasMore、unreadCount`；unreadCount 是本人全部未读数，不随当前分页缩小。通知项仅有 `id、type、title、content、orderNo、waitlistNo、createdAt、readAt`，不返回内部事件 ID、用户 ID 或原始 payload。游标只能引用本人通知；停用或演示账号不能访问。预约处罚不影响阅读已有通知。

已读接口不需要 Idempotency-Key，也不接受客户端指定用户或时间。成功返回通知 id 和首次 readAt；非本人及不存在的通知都返回 404。并发点击仅首次写入时间，其余返回相同结果。

### 通知生成与 MQ 投递

链路为：业务事务保存 Outbox → 发布器领取事件 → RabbitMQ → 通知消费者同事务保存 notification 和 consumer_inbox → 监听方法成功返回后容器 ACK。业务提交后，发布器每轮间隔 5 秒扫描，最多 50 条，单轮超过 10 秒不再处理下一条；网络发送时不持有数据库事务锁。60 秒租约到期后可重新领取，旧租约的迟到确认或失败记录不能覆盖新尝试。

当前支持预约创建、取消、爽约、签到、完成，以及候补转正、取消、过期、拒绝九类通知。候补转正的 BOOKING_CREATED 事件只记录消费完成，由 WAITLIST_MATCHED 生成一条“候补成功”，避免重复提示。事件快照只表达当时发生的事情，点击订单仍读取当前状态。

拓扑为 durable direct exchange `flashslot.events`，通知队列 `flashslot.notification`；死信 exchange 为 `flashslot.dlx`，死信队列 `flashslot.notification.dead`。发布消息为 persistent，必须收到 publisher confirm ACK 且没有 mandatory return 才标记 PUBLISHED；这只表示 broker 接收成功，不代表用户已读或消费者成功。失败按 1、5、30、120、600 秒退避，之后封顶 600 秒，第 12 次进入 FAILED。确认丢失或提交前崩溃允许重投，由 Inbox 与通知唯一约束防重。

消费失败最多尝试 3 次，重试间隔约 1 秒、2 秒，仍失败或无法解析则由 recoverer 先将原文提交至数据库 `dead_letter`，提交成功后才允许容器 ACK；保存失败抛出立即重新入队异常，原消息保留在业务队列。通知与撮合均采用此流程，不依赖 classic 队列的 DLX 转发可靠性。现有死信队列和监听器保留用于接收旧版本或 broker 转入的消息，不需要修改已有队列类型。通知插入后 Inbox 写入失败会一起回滚；重新投递后只保存一条通知。已有消息仍会为停用账号落库，但该账号不能查询，直到恢复启用。

`MESSAGING_ENABLED=false` 关闭 MQ 监听和自动发布，业务及撮合派发仍保存 Outbox，但自动撮合将等待 MQ 恢复；`MESSAGING_SCHEDULING_ENABLED=false` 只关闭发布扫描。默认 broker 为 localhost:5672，使用 RABBIT_* 配置。排期和信用事件、用于审计的原始 RELEASE_REQUESTED/COMPLETED/MATCHED 不进通知队列；专用 RELEASE_DISPATCHED 负责撮合。FAILED 投递重试、通知与撮合消费死信均可在管理员异常处理页恢复。当前没有 WebSocket、真实短信或邮件推送。

## 签到、爽约与完成

管理员调用 `GET /api/v1/admin/resources/{id}/check-in-code` 获取设备专属签到码，返回 `resourceId、checkInCode、expiresAt`。接口要求当前数据库账号仍为启用的管理员，响应带 `Cache-Control: no-store`。签到码有效期 60 秒，包含设备 ID、签发/到期时间、随机 nonce 和 HMAC 签名，与登录 JWT 使用不同格式和签名域；现场展示方需要刷新过期码。当前仅提供凭据接口，不包含二维码页面，也不能据此证明用户真实到场。

订单本人调用 `POST /api/v1/bookings/{orderNo}/check-in`，请求头携带登录 Bearer Token，请求体：

```json
{"checkInCode":"管理员展示的设备短时签到码"}
```

成功 HTTP 200，返回当前订单详情及首次 `checkedInAt`。只允许启用的正式账号操作本人订单，管理员不能替他人签到；预约处罚期和设备 PAUSED 不阻止已有合法订单签到。签到窗口为 `[开始前 10 分钟, 开始后 15 分钟)`，恰好到截止点禁止签到；尚未开放返回 `422 CHECKIN_NOT_OPEN`，错误/过期/其他设备的码返回 `422 CHECKIN_CODE_INVALID`，已取消返回 `422 INVALID_STATE`，非本人订单返回 404。首次签到更新订单、日历 revision 和 Outbox 同事务提交。

签到不需要 `Idempotency-Key`，通过订单锁和状态实现幂等：已签到或已完成订单重复请求返回原签到时间，无需仍然有效的现场码。超时请求先提交 NO_SHOW、扣分、占用释放及事件，再返回 `422 CHECKIN_EXPIRED`；即使后台任务延迟，也不能迟到签到。请求身份从 `BaseContext` 获取，取得资源共享锁 → 日历锁 → 订单锁 → 用户锁后再读取当前时间，保证与取消和到期任务协调。

自动到期任务在启动 5 秒后开始，每次结束后间隔 5 秒扫描，使用独立调度线程，单批最多 100 条，按截止时间和订单 ID 排序。扫描一轮时固定到期截止时间，以 `(到期时间, ID)` 游标跨调度继续；失败记录也推进游标，避免最早 100 条异常阻塞后续健康订单，扫完后从头重试。固定截止时间保证持续新增记录不会阻止扫描绕回；游标仅在进程内，重启从头扫描，业务状态和约束负责幂等。CONFIRMED 且签到截止已到转为 NO_SHOW；CHECKED_IN 且结束时间已到转为 COMPLETED，保留签到时间与历史占用。每条订单使用独立事务，单条失败回滚并记录日志；多实例同时扫描只会生效一次。可通过 `BOOKING_EXPIRY_SCHEDULING_ENABLED=false` 关闭本任务，不影响候补扫描开关。

爽约每单只扣一次 10 分、最低到 0，分数低于 60 时延长固定 7 天预约限制；封禁或角色变更不会阻止后台结算。有有效可承接候补时，只将尚未开始的设备格转为 HANDOFF、删除相应用户格并创建释放任务；无候补时直接释放。已开始格保留历史记录，有候补时立即派发撮合。例如 09:00–11:00 的订单在 09:15 爽约，09:00–09:30 保留，09:30–11:00 有候补时进入交接，无候补时直接释放。若任务迟到至整个区间已结束，仍记爽约但不创建无未来时段的释放任务。每日跨设备合计最多 8 格（4 小时），用户锁内汇总 charged_slots；已开始的格仍计入当日配额。

## 日历与排期接口

| 方法 | 地址 | 行为 |
| --- | --- | --- |
| GET | `/api/v1/resources/{id}/schedule?date=2026-10-16` | 公开查询某天 48 个半小时格，登录身份仅能查看本人订单号 |
| PUT | `/api/v1/admin/resources/{id}/days/2026-10-16` | ADMIN 修改一个已有日历，预期 revision 放在 DTO 中 |

修改日历请求：

```json
{"revision":0,"enabled":true,"openStartSlot":16,"openEndSlot":44,"reason":"调整实验室开放时间"}
```

响应为 `Result<ResourceDayView>`，data 包含 resourceId、bookDate、enabled、openStartSlot、openEndSlot、revision。修改成功 revision 加 1。
查询响应为 `Result<ScheduleView>`，data 包含设备状态、当天开放范围、revision、serverTime 和 slots。
每格包含 slotIndex、startAt、endAt、state、mine、orderNo、canBook、canWaitlist。时间点为 UTC，bookDate 按 Asia/Shanghai。

- 状态按 PAST（格开始时刻已到）、CLOSED（未开放）、BOOKED/HANDOFF、FREE 判定。PAUSED 设备可显示 FREE，但 canBook/canWaitlist 为 false。
- 预约与候补申请需要严格早于格开始前 30 分钟；恰好到截止点不可申请。格子的可申请标记仅是排期提示，不代表用户信用、配额或连续区间等资格校验已经通过。
- 公共查询不会返回其他用户的身份或订单号。只有数据库中仍启用的当前账号可以看到自己占用格上的 orderNo。
- 只读查询使用同一个可重复读快照；读取当前日历revision后，以版本键访问Redis公开占用缓存，本人订单另行查询，详情见下方缓存说明。
- 修改遵循资源共享锁 → 日历排他锁 → 当前管理员锁；日历必须提前存在。关闭或缩短时，有未结束 CONFIRMED/CHECKED_IN、未过期 WAITING 或当前/未来 HANDOFF 就返回 409 CALENDAR_IN_USE；扩大范围允许。
- 过去日历不能修改；未生成日历返回 503 CALENDAR_NOT_READY；设备不存在 404；旧 revision 返回 409 VERSION_CONFLICT。退役设备不能重新开放日历。
- 日历变更、SCHEDULE_CHANGED Outbox 和审计同事务，任一步失败整体回滚。缓存通过当前数据库revision换键立即失效，不依赖MQ消费者；SCHEDULE_CHANGED保留为业务事件，发布器不会将其误标为通知已投递。

Controller、Service、Mapper 的新增方法与时间格辅助方法均提供用途和约束注释。
设备创建时生成 14 天日历；应用启动及每分钟补齐上海今天起 14 天内的缺失日历，不在排期查询中隐式生成，也不覆盖已有配置。

## 实体约定

本项目一张表对应一个持久化实体，不代表每个实体都需要公开 CRUD 接口。
DTO 定义请求字段、VO 定义响应展示；实体不直接返回前端，尤其不能暴露密码哈希。

- Lombok `@Getter`、`@Setter`、`@NoArgsConstructor`；不默认生成 `toString/equals/hashCode` 或全字段构造。
- 请求 DTO、响应 VO、Result 统一使用普通 Java class，在类中声明 private 字段，并用 Lombok 简化 Getter/Setter/构造方法，不使用 record。`@RequestBody @Valid` 绑定并验证 JSON，列表查询使用 `@ModelAttribute @Valid ResourceQueryRequest` 绑定 URL 参数。
- `BIGINT → Long`、时间格及计数 → `Integer`、`BOOLEAN → Boolean`、`DATE → LocalDate`。
- UTC `DATETIME(3) → Instant`，统一使用 `UtcInstantTypeHandler`；JSON → `JsonNode`，使用 JacksonTypeHandler；BINARY(32) → byte[]。
- 状态当前使用 String，保持 SQL 枚举原值；业务层实现时再封装状态转换，不把数据库 CHECK 当作完整状态机。
- 自增单主键使用 `@TableId(AUTO)`；事件 UUID 等应用分配的单主键使用 INPUT。
- ResourceDay、SlotOccupation、UserSlotOccupation、ApiIdempotency、ConsumerInbox 是联合主键表：明确映射每个字段，不标假单主键，不使用 BaseMapper 的单 ID CRUD。使用显式 SQL，WHERE 条件包含完整业务键。
- WaitlistRecord.activeMarker 是数据库生成列，禁止 INSERT/UPDATE 写入。
- 设备 Resource 使用 `@Version` + `OptimisticLockerInnerInterceptor`，通过 MyBatis-Plus `updateById` 按旧版本更新并递增；其他实体的 version/revision 仍按各自业务 SQL 维护，不能用设备配置乐观锁代替预约事务与状态机。
- ResourceMapper、AdminAuditMapper、AccountPermissionMapper 继承 `BaseMapper`；设备服务继承 `ServiceImpl`，接口继承 `IService`，基础 CRUD 使用 MyBatis-Plus，列表使用 LambdaQueryWrapper。联合主键日历批量写入与账号 `FOR UPDATE` 仍使用显式 SQL，不假设框架支持联合主键单 ID CRUD。
- 时间字段没有实现自动填充。Resource 的创建/更新时间禁用实体 INSERT/UPDATE 写入，交给数据库默认值和 ON UPDATE 维护，避免 updateById 回写旧时间戳。
- 外键仅映射 ID 字段，不生成实体之间的嵌套对象或 JPA 关系。

## 构建与启动

在工作区根目录：

```powershell
mvn -f flashslot/pom.xml clean package
```

准备独立空的 `flashslot` MySQL 数据库及具备迁移权限的账号，在当前 PowerShell 中设置：

```powershell
$env:DB_URL = 'jdbc:mysql://localhost:3306/flashslot?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8'
$env:DB_USERNAME = 'flashslot'
$env:DB_PASSWORD = '填写你自己的数据库密码'
$env:APP_PROFILE = 'local'
# 还需设置上述 JWT_SECRET，复用当前部署安全保存的密钥。
java -jar flashslot/target/flashslot-0.0.1-SNAPSHOT.jar
```

默认端口 8080，可通过 SERVER_PORT 修改。local 文档：`http://localhost:8080/swagger-ui/index.html`；OpenAPI：`/v3/api-docs`；就绪探针：`/actuator/health/readiness`。
文档展示登录、设备、日历排期、创建预约、我的订单和订单详情接口。非 local profile 默认关闭文档。

Redis/RabbitMQ 配置使用 REDIS_HOST/REDIS_PORT/REDIS_PASSWORD 和 RABBIT_HOST/RABBIT_PORT/RABBIT_USERNAME/RABBIT_PASSWORD。
通知模块默认连接 RabbitMQ，需按上述环境变量配置已启动的 broker；通知网络发送不在预约事务内，MQ 故障不会撤销已提交订单。Redis缓存可关闭或故障回源。就绪探针仅包含数据库和应用就绪状态。
消费者已启用，需要可用的中间件；Compose 与运行说明见 [部署目录](../deploy/flashslot/README.md)。

Flyway 在应用启动时执行 V1–V10，对应上层 `db/01–10` 的当前 16 表版本；V10 增加历史归档状态与死信上下文，不增加表。
**使用 Flyway 管理的新库不要先手工执行 db SQL。** 已有手工建表的库需要单独核对和建立迁移基线，当前不自动 baseline。
迁移一旦应用就保持不可变；后续结构变化新增下一编号迁移，不修改已部署脚本。

## 本地隔离验证

PowerShell 7、JDK 21、Maven，以及 PATH 中的 MySQL 8.0 mysql/mysqld：

```powershell
./scripts/verify-flashslot-foundation.ps1
```

脚本创建私有临时 MySQL 数据目录、随机本机端口，检查实际 datadir 后才创建测试库。
运行 Maven clean verify，结束后关闭自己的 MySQL 并清理临时目录，恢复环境变量。
验证真实应用启动、五个迁移、重复迁移无新增操作、14 表实体列映射、JSON 中文读写、UTC 跨日与多个 JVM 时区、OpenAPI、就绪探针和默认访问限制。
设备接口通过 MockMvc + 真实私有 MySQL 验证新增/查询/分页、日历和审计、参数拒绝、权限实时检查、版本冲突、并发修改仅一次成功及权限失败时的事务回滚。
日历排期验证包含 48 格状态、本人隐私、截止边界、PAUSED 提示、有效预约/候补/HANDOFF 阻止关闭或缩短、并发 revision 冲突，以及审计失败时日历和 Outbox 同时回滚。
订单查询验证包含创建后读取、同毫秒游标翻页及插入新订单、归属隔离、账号状态与管理员双重权限、当前状态读取、释放任务关联及敏感字段过滤。
登录验证使用真实 BCrypt 账号和签发的 Token，覆盖管理员建设设备 → 普通用户预约 → 订单查询，以及权限撤销、Token 篡改/到期/用途校验、禁用账号、无状态认证、登录限流与账号别名。
取消与候补验证包括正常/重复/并发取消、管理员原因与降权后幂等回放拒绝、精确时间边界、提前结束/爽约/完成、信用下限和处罚期限、占用归属异常及事件失败整体回滚；候补占用条件、幂等重复、跨设备并发日上限、截止点、队列位置、本人游标分页及隐私隔离。
自动撮合验证包括取消后转正、重复及双 worker 执行、队首失格/用户冲突、截止过期、迟到后缀重选、无人候补释放、事务失败回滚和短退避重试、处理预算及人工检查状态；独立验证取消即派发、补偿任务注册与关闭开关。
签到与自动到期验证包括管理员当前权限、签到码篡改/过期/设备绑定、窗口边界、本人隔离/禁用/角色、处罚期与暂停设备、重复和并发签到、截止时刻签到与扫描竞争、并发扫描仅扣一次、完成保留历史、事件失败整体回滚及下轮重试；独立验证 5 秒任务注册与关闭开关。
报告位于上层 `.run/flashslot-foundation-verification.json`，详细 JUnit 结果在 `target/surefire-reports`。
新增接口验证覆盖管理员当前权限、审计筛选和同毫秒游标、通知归属和字段白名单、未读数、重复及并发首次已读。执行 `./scripts/verify-flashslot-foundation.ps1 -WithRabbit` 还会启动独立临时 RabbitMQ（需要本机 Erlang，首次下载官方 3.13.7 测试发行包到临时缓存），随机回环端口与现有 broker 隔离，结束后关闭并清理。MQ 验证包括实际业务事件到通知、确认/无法路由/NACK/超时、重复和并发消费、通知插入后失败回滚、死信重新投递、租约恢复及旧结果隔离、失败上限和候补提示防重。普通数据库验证默认关闭 MQ，不连接用户现有 broker。
普通 Maven 构建会跳过需要私有数据库的集成测试；Testcontainers 依赖已经引入，当前验证使用本机临时 MySQL，不需要 Docker。

## 候补收尾与管理员恢复

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| POST | `/api/v1/me/waitlists/{number}/cancel` | 本人取消待处理候补；已到期归一为EXPIRED；已转正返回409 |
| GET | `/api/v1/admin/release-jobs` | 查询MANUAL_REVIEW释放任务，cursor为ID，limit默认20/最大100 |
| POST | `/api/v1/admin/release-jobs/{number}/retry` | 核对来源订单和全部占用，重置为PENDING并同事务派发，提交后立即撮合 |
| POST | `/api/v1/admin/release-jobs/{number}/release` | 核对归属后人工释放本任务占用，更新日历版本 |
| GET | `/api/v1/admin/messages` | 查询FAILED发布事件，不返回原始payload；cursor为eventId |
| POST | `/api/v1/admin/messages/{eventId}/retry` | 重置支持的FAILED通知事件为NEW，保留eventId防重复消费 |
| POST | `/api/v1/admin/users/{userId}/credit-adjustments` | delta为非零整数，调整后信用分仍须在0至100之间 |
| POST | `/api/v1/admin/users/{userId}/restriction-clear` | 解除预约限制，不改变信用分 |

新增POST都要求`Idempotency-Key`。管理员请求体必须包含1至255字`reason`；信用调整另需`delta`。结果按Result包装，成功HTTP200；重复键同参数回放首次结果，换参数409。幂等结果回放前仍复核当前权限。

候补取消与转正使用资源→日历→候补→用户锁序，同一候补不能同时取消和转正。取消/过期事件与状态同事务。全局到期清理每5秒最多100条、每条独立事务，不依赖释放任务存在；与订单扫描一样固定一轮截止时间，以 `(expire_at, ID)` 游标跨批越过失败项，扫完后重新尝试。多实例重复扫描只产生一次状态变更/过期事件。该扫描随`RELEASE_SCHEDULING_ENABLED`关闭，任务创建与设备恢复仍会同事务写入即时派发事件。WAITLIST_CANCELED也会生成站内通知。

管理员操作与审计、幂等结果同事务；信用调整还包含ADMIN_ADJUST信用流水。人工释放不能越过归属异常，需先核查数据。限制解除与分数调整分开进行。FAILED重试仅针对Outbox发布失败，不能把PUBLISHED或正在发送的消息强行重置；消费端死信自动持久保存至 dead_letter，管理员可带原因恢复消费，原事件 ID 和 Inbox 防重机制保持不变。

## Redis排期缓存与本机前端

### 账号停用与热点缓存（本地）

- 已移除 Redis 封禁缓存、版本墓碑与提交后同步回调。JWT 请求先验签和时间，再检查数据库 enabled、credential_version 与 revoked_token；停用/启用均在账号事务中递增凭据版本，旧 Token 不能在启用后复活。停用账号密码登录由 Spring Security 拒绝，不依赖 Redis 缓存。数据库状态是唯一授权事实，Redis 淘汰或重启不影响停用与撤销。
- 账号封禁对应管理员停用；信用处罚的 restriction_until 仍只限制新预约，允许用户登录查看记录、取消和履行已有预约，不混用两种限制。
- 设备详情使用 `flashslot:resource:{id}:{version}`；每次先查数据库version，再访问对应缓存，版本和详情在同一REPEATABLE_READ快照中读取。创建24小时内视为新设备优先缓存，也可通过 `HOT_RESOURCE_IDS=1,2,3` 配置固定热点。热点详情TTL120–180秒，普通详情20–40秒。不存在设备使用 `:missing` 空值键5–10秒，减少重复详情查询；版本查询仍访问数据库，不能宣称不存在ID完全不查库。新增设备有version=0，会直接忽略旧missing键。
- 排期公共占用缓存按设备/日期/revision隔离，普通TTL30–45秒、热点60–90秒；每次独立随机偏移，分散集中失效。设备状态、日历版本、本人信息和时间资格仍每次从数据库快照与服务器时间计算，不把个性化可预约标记缓存给其他人。
- 缓存未命中使用Redisson分布式锁并二次检查缓存。`tryLock(waitTime, TimeUnit)` 不传固定leaseTime，看门狗默认30秒并自动续期；等待1秒仍无缓存返回503，让客户端重试，避免等待者集体回源。锁只负责重建，不作为预约库存事实来源。
- 设备更新与version递增、审计在同一数据库事务中完成，提交后删除旧版本键和missing键。新请求根据数据库新版本访问新键，即使删除失败或旧请求晚到回填，也只留下不会被新请求使用的旧版本键，随后TTL清理。锁按设备ID和版本区分，旧版本慢请求不阻塞新版本加载。Redis故障时回源数据库并短暂退避网络重试；事务回滚不会发布新版本或删除原缓存。版本以数据库为准，不使用Redis随机标记。
- 预约继续执行资源/日历/用户锁、时间格唯一约束、幂等、提前30分钟、7天窗口、跨设备不重叠和每日8格限制。Redis缓存失效、热点流量或锁续期不改变这些规则。看门狗只在持锁进程正常续期时维持锁，不能代替数据库约束。

新增配置：`app.cache-lock.watchdog-timeout-ms=30000`、`HOT_RESOURCE_IDS`。Redisson 3.52.0 复用Spring Redis的主机、端口、用户、密码和数据库配置，延迟连接，不另建公网Redis。

排期先在只读快照中读取设备、日历，使用`flashslot:schedule:{resourceId}:{date}:{revision}`缓存公开的格子占用，TTL按上述冷热策略随机取值。预约/取消/转正/日历调整提交后revision改变，下一次查询立即使用新键；旧查询即使晚到回填也只填旧键，不覆盖新版本。因此无需等待异步MQ失效，SCHEDULE_CHANGED 等已登记留痕事件直接进入 ARCHIVED，不参与 MQ 投递；V10 归档旧 NEW，默认 90 天后每小时短事务分批清理。PUBLISHED/Inbox 继续保留以维护防重。

缓存不含个人订单号、用户ID、密码或最终可预约标记；启用账号的本人订单信息在当前快照内单独查询并覆盖显示。开放状态和时间截止每次按当前时间计算。Redis不可用时回源MySQL，并短暂停止缓存访问15秒后尝试恢复；缓存损坏按未命中处理。`SCHEDULE_CACHE_ENABLED=false`可关闭。当前优化减少重复的公开占用联查，仍读取数据库版本与必要的本人占用，不承诺无数据库查询。

Vue3/Element Plus前端位于`../flashslot-web`，运行方法见其README。`../scripts/start-flashslot-local.ps1`启动独立本机体验环境并提供随机密码，`stop-flashslot-local.ps1`按PID、启动时间和路径核对后停止自己启动的服务，保留体验数据库。

完整验证：`pwsh -NoProfile -File scripts/verify-flashslot-foundation.ps1 -WithRabbit -WithRedis`。测试启动随机回环端口的独立MySQL、RabbitMQ、Redis并清理，不连接既有服务。可用`-Test RecoveryIntegrationTest`缩小验证范围。


### 注册能力与消息治理补充

`GET /api/v1/auth/capabilities` 是公开、no-store 的能力接口，返回 `registrationEnabled` 与 `registrationMode=DEVELOPMENT|DISABLED`。登录/注册页默认关闭入口，获取到明确启用后才允许操作；生产未接短信时提示联系管理员。此接口不提供验证码，不能绕过注册服务端校验。

消息历史类型由 PublishedEvent 显式登记，未知类型拒绝写入，避免拼写错误静默归档。V10 在原有 16 张表上升级；历史归档清理不删除 NEW/SENDING/FAILED/PUBLISHED、Inbox 或死信。死信按来源队列和正文隔离，保存受控失败原因；管理列表不返回原始正文、哈希或异常堆栈。

[消息指标](../docs/message-metrics.md)包括发布/消费结果与耗时、数据库积压快照和任务逾期；[部署说明](../deploy/flashslot/README.md)提供可选 Prometheus 配置。抓取端点按实际连接来源网段授权，不信任转发头。
