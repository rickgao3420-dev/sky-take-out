# Java 程序与微信小程序通信 — 原理与身份标识全解析

## 一、总体架构：一次请求的完整旅程

本项目（苍穹外卖）是典型的 **小程序(微信端) + 后端(Spring Boot) + 数据库** 三层架构。小程序端运行在用户手机上（微信客户端内），后端运行在服务器上，两者通过 **HTTPS + JSON** 进行通信。

```
┌────────────────────────┐         HTTPS/JSON          ┌──────────────────────────────┐
│      微信小程序端        │ ◄─────────────────────────► │        Java 服务端            │
│  (微信客户端内运行)       │   请求(Request)/响应(Response) │   Spring Boot + MyBatis       │
│                        │                             │                              │
│  wx.request({url, ...})│                             │  DispatcherServlet            │
│                        │                             │   → 拦截器(鉴权)               │
│  ─── 用户操作 ───►      │                             │   → Controller(接收参数)       │
│  ─── 收到数据渲染 ──►    │                             │   → Service(业务逻辑)          │
└────────────────────────┘                             │   → Mapper(操作数据库)         │
                                                       │   → 消息转换器(Java↔JSON)      │
                                                       └──────────────────────────────┘
```

### 1.1 通信的本质

| 概念 | 说明 |
|------|------|
| 传输协议 | HTTPS（TLS 加密的 HTTP），保证数据在公网上不被窃听、篡改 |
| 数据格式 | JSON（`application/json`），Java 用 fastjson / Jackson 序列化，小程序端直接解析 |
| 请求方式 | RESTful：`GET`（查询）、`POST`（提交/登录）等 |
| 无状态性 | HTTP 本身无状态，**每次请求都是独立的**，服务端靠"令牌"识别身份 |

### 1.2 为什么不能"记住登录状态"（引出 token）

网页时代常用 `Session + Cookie`：服务端存 session，客户端带 cookie。但小程序端：
- 没有浏览器 cookie 机制；
- 服务端存 session 占用内存、且多实例部署（集群）时 session 无法共享。

所以本项目采用 **JWT（JSON Web Token）无状态认证**：登录成功后服务端签发一个加密令牌，之后每次请求由客户端放在请求头里带回来，服务端验签即可确认身份——**服务端不保存任何会话状态**。

---

## 二、通信原理拆解（以微信登录为例）

### 2.1 时序图

```
微信小程序                         Java 服务端                           微信服务器
    │                                  │                                    │
    │ 1. wx.login() 获取 code           │                                    │
    │    (code 为一次性登录凭证)          │                                    │
    │─────────────────────────────────►│                                    │
    │                                  │ 2. GET /user/user/login {code}     │
    │─────────────────────────────────►│                                    │
    │                                  │ 3. GET jscode2session              │
    │                                  │    appid+secret+js_code+grant_type │
    │                                  │───────────────────────────────────►│
    │                                  │◄───────────────────────────────────│
    │                                  │    返回 {openid, session_key, ...}  │
    │                                  │ 4. 查 user 表(按 openid)            │
    │                                  │ 5. 不存在则 insert 自动注册          │
    │                                  │ 6. 用 userId 生成 JWT               │
    │ 7. 返回 {id, openid, token}      │                                    │
    │◄─────────────────────────────────│                                    │
    │ 8. 后续请求头携带 token           │                                    │
    │─────────────────────────────────►│  拦截器验签 → 放行                  │
```

### 2.2 关键代码对照

**① 小程序端**（不在本项目内，属于前端工程）：

```js
wx.login({
  success: (res) => {           // res.code 就是临时登录凭证
    wx.request({
      url: 'https://xxx/user/user/login',
      method: 'POST',
      data: { code: res.code },
      header: { 'content-type': 'application/json' },
      success: (resp) => {
        // resp.data = { code:1, data:{ id, openid, token } }
        // 将 token 存起来，后续所有请求放在 header.authentication 中
      }
    })
  }
})
```

**② 服务端入口** — `UserController.login`（`UserController.java:43`）：

```java
@PostMapping("/login")
public Result<UserLoginVO> login(@RequestBody UserLoginDTO userLoginDTO){
    // 1. JSON → Java 对象：Spring 用 Jackson 反序列化请求体为 UserLoginDTO
    User user = userService.wxLogin(userLoginDTO);   // 2. 业务逻辑
    // 3. 用 userId 生成 JWT
    Map<String, Object> claims = new HashMap<>();
    claims.put(JwtClaimsConstant.USER_ID, user.getId());
    String token = JwtUtil.createJWT(jwtProperties.getUserSecretKey(),
                                     jwtProperties.getUserTtl(), claims);
    // 4. Java 对象 → JSON：JacksonObjectMapper 序列化后响应给小程序
    return Result.success(UserLoginVO.builder().id(user.getId())
            .openid(user.getOpenid()).token(token).build());
}
```

**③ 服务端核心业务** — `UserServiceImpl.wxLogin`（`UserServiceImpl.java:35`）：

```java
public User wxLogin(UserLoginDTO userLoginDTO) {
    String openid = getOpenid(userLoginDTO.getCode());   // 调微信接口换 openid
    if (openid == null) throw new LoginFailedException(MessageConstant.LOGIN_FAILED);
    User user = userMapper.getByOpenid(openid);          // 查库
    if (user == null) {                                  // 新用户自动注册
        user = User.builder().openid(openid)
                .createTime(LocalDateTime.now()).build();
        userMapper.insert(user);
    }
    return user;
}
```

**④ 消息转换器** — `WebMvcConfiguration.extendMessageConverters`（`WebMvcConfiguration.java:79`）

Spring MVC 默认用 Jackson 完成 Java 对象 ↔ JSON 的双向转换。项目自定义了 `JacksonObjectMapper` 并在**转换器列表第一位**注册，专门处理 `LocalDateTime` 等 Java 8 时间类型的序列化格式——这就是为什么接口返回的时间是字符串而不是数组。

---

## 三、身份标识全解析（核心章节）

整个通信过程中会出现很多"id / token / key"，容易混淆。先给总览表，再逐个拆解：

| 名称 | 归属方 | 生命周期 | 用途 | 本项目出现位置 |
|------|--------|---------|------|---------------|
| `code` | 微信服务器 | 5 分钟、一次性 | 临时登录凭证，换 openid | 小程序 `wx.login()` → `UserLoginDTO.code` |
| `appid` | 微信服务器 | 长期 | 小程序唯一标识（"账号名"） | `application-dev.yml` → `WeChatProperties.appid` |
| `secret` | 微信服务器 | 长期 | 小程序密钥（"密码"），仅服务端持有 | `WeChatProperties.secret` |
| `openid` | 微信服务器 | 长期 | 用户在**当前小程序**下的唯一 ID | `User.openid`，存数据库 |
| `session_key` | 微信服务器 | 与 code 绑定 | 解密手机号等敏感数据用，后端一般不保存 | 本项目未使用 |
| `unionid` | 微信服务器 | 长期 | 同一微信开放平台账号下跨应用统一的 ID | 本项目未使用 |
| `access_token` | 微信服务器 | 2 小时 | 调用微信"服务端类"接口（如获取手机号）的令牌 | 本项目未使用 |
| JWT `token` | **自己的服务端** | ttl 内有效（2 小时） | 识别已登录用户/员工 | `JwtUtil` 签发/校验 |
| `admin-secret-key` | 自己服务端 | 长期 | JWT 签名密钥（HS256），必须保密 | `application.yml` |
| `user-secret-key` | 自己服务端 | 长期 | 用户端 JWT 签名密钥，与 admin 分开 | `application.yml` |
| `admin-token-name` / `user-token-name` | 自己服务端 | — | 请求头中令牌的**名字**（不是令牌本身） | `JwtTokenAdminInterceptor` |
| `mchid` / `mchSerialNo` / `apiV3Key` 等 | 微信支付 | 长期 | 支付签名、证书相关 | `WeChatPayUtil`（支付阶段使用） |
| `SHOP_STATUS` | 自己的 Redis | 长期 | Redis 键名，存店铺营业状态 | `RedisConstant.SHOP_STATUS` |
| `BaseContext` | 自己的服务端 | 单次请求内（ThreadLocal） | 临时存放当前登录用户 id | `BaseContext` |

---

### 3.1 `code` — 一次性登录凭证

- **谁产生的**：小程序端调用 `wx.login()` 后由微信客户端生成。
- **特点**：有效期约 5 分钟，**只能用一次**，用完即废。
- **作用**：它是小程序"临时通行证"，本身不含任何用户信息。后端拿它去找微信服务器兑换 `openid`。
- **为什么不用它当令牌**：因为一次性 + 有效期短，无法用于后续请求的身份识别。

> 在 `UserServiceImpl.getOpenid()`（`UserServiceImpl.java:83`）中，code 作为 `js_code` 参数发给微信接口。

### 3.2 `appid` — 小程序的"账号名"

- 微信公众平台为每个小程序分配的全局唯一 ID（形如 `wx3efdba50c67564ae`）。
- 相当于小程序在微信体系中的"用户名"，标识"我是谁"。
- **可以在前端代码中暴露**，不算机密。

### 3.3 `secret` — 小程序的"密码"

- 与 appid 配套，相当于"密码"，**只能保存在服务端**，严禁放入小程序前端代码（会被反编译窃取）。
- 泄露后果：别人拿到 appid+secret 就能冒充你的小程序调用微信接口（如获取用户手机号）。

```yaml
# application-dev.yml
sky:
  wechat:
    appid: wx3efdba50c67564ae
    secret: ae5b5ba314bf232738ea0fa7d76ba62c   # ⚠️ 真实凭据，勿外泄
```

`WeChatProperties`（`WeChatProperties.java:9`）通过 `@ConfigurationProperties(prefix = "sky.wechat")` 把这两项注入到 Java 代码中。

### 3.4 `openid` — 用户的"身份证号"

- 微信服务器根据 `appid + 用户的微信身份` 计算出的**用户在当前小程序下的唯一 ID**。
- **同一用户，不同小程序，openid 不同**（以 appid 为因子）。
- 它是本项目用户表的**业务主键判断依据**：`user` 表没有"用户名/密码"，`openid` 就是账号本体。

```java
// UserServiceImpl.java:47-56 — 核心业务
User user = userMapper.getByOpenid(openid);   // 有 → 老用户直接登录
if (user == null) {                            // 无 → 自动注册
    user = User.builder().openid(openid)
            .createTime(LocalDateTime.now()).build();
    userMapper.insert(user);
}
```

**这是"微信登录即注册"的实现本质**：第一次登录时数据库没有这条 openid，就插入一条新记录；之后每次登录都能查到，即"免密登录"。

### 3.5 `session_key` — 解密敏感数据的密钥

- `jscode2session` 接口除返回 `openid` 外，还返回 `session_key`。
- 用途：配合前端传来的加密数据解密出**手机号、微信昵称**等敏感信息（`getPhoneNumber` 等能力）。
- 本项目只在登录链路用 openid，**不保存 session_key**（保存会带来存储与失效管理负担；需要手机号时再临时换取即可）。

### 3.6 `unionid` — 跨应用的统一 ID（拓展）

- 同一主体（公司）注册了多个小程序/公众号，并绑定到同一个"微信开放平台账号"后，同一用户在这些应用下的 `unionid` 相同。
- 用 openid 无法跨应用识别同一用户，unionid 可以。本项目单小程序，用不到。

### 3.7 `access_token` — 调用微信服务端接口的令牌（拓展）

- 某些微信接口（获取手机号、发送订阅消息、上传素材等）需要先用自己的 `appid+secret` 调用 `GET /cgi-bin/token` 换取 `access_token`。
- 有效期 2 小时，**需要自己缓存并定时刷新**（通常存 Redis）。
- 与登录链路无关，本项目暂未使用，但支付/消息推送阶段会接触。

### 3.8 JWT `token` — 服务端签发的认证令牌（重点）

这是**自己服务端生成**的令牌，与微信无关。登录成功后由 `JwtUtil.createJWT` 签发，随响应返回给小程序。

**JWT 由三段组成，用 `.` 分隔：**

```
eyJhbGciOiJIUzI1NiJ9.eyJ1c2VySWQiOjEsImV4cCI6MTc4MTIzNDU2N30.4dF8x...

┌─────────────────────┐ ┌──────────────────────────┐ ┌──────────────┐
│ Header (base64)      │ │ Payload (base64)          │ │ Signature    │
│ {"alg":"HS256"}      │ │ {"userId":1,"exp":...}    │ │ HMAC-SHA256  │
│ 签名算法声明           │ │ 携带的身份数据(claims)      │ │ header+payload│
└─────────────────────┘ └──────────────────────────┘ └──────────────┘
                    secretKey 参与签名，服务端验签
```

**本项目两套 JWT 配置**（`application.yml` 的 `sky.jwt` 段，绑定到 `JwtProperties`）：

| 配置项 | 管理端(admin) | 用户端(user) | 说明 |
|--------|--------------|--------------|------|
| secret-key | `itcast` | `itheima` | 签名密钥，两套隔离 |
| ttl | `7200000` ms = 2 小时 | `7200000` ms = 2 小时 | 过期时间 |
| token-name | `token` | `authentication` | **请求头里放令牌的字段名** |
| claims 载荷 | `empId` | `userId` | 签发时写入的身份标识 |

**校验侧** — `JwtTokenAdminInterceptor.preHandle`（`JwtTokenAdminInterceptor.java:35`）：

```java
// 1. 从请求头取令牌
String token = request.getHeader(jwtProperties.getAdminTokenName());
// 2. 用同一个 secretKey 验签；签名/过期不一致会抛异常 → 401
Claims claims = JwtUtil.parseJWT(jwtProperties.getAdminSecretKey(), token);
Long empId = Long.valueOf(claims.get(JwtClaimsConstant.EMP_ID).toString());
// 3. 存入 ThreadLocal，后续 Service 层可直接取当前登录人 id
BaseContext.setCurrentId(empId);
```

**为什么要用 JWT 而不用别的**：
- **无状态**：服务端不存 session，验签即验身份，天然支持多实例部署；
- **防篡改**：payload 内容被改动后，验签必然失败；
- **payload 可读但不可伪造**：base64 只是编码不是加密，所以**不能放密码等敏感数据**，本项目只放 id。

> ⚠️ 当前 `WebMvcConfiguration`（`WebMvcConfiguration.java:40`）只注册了 admin 拦截器（拦截 `/admin/**`），**C 端用户的 `JwtTokenUserInterceptor` 尚未编写**。也就是说 `/user/**` 接口目前未做鉴权，这是后续待完成的工作。

### 3.9 `secretKey`（JWT 签名密钥）与 token-name 的区别

- **secretKey**：签发/验签 JWT 的密钥，**只能存在于服务端**，泄露 = 任何人都能伪造登录令牌。
- **token-name**：只是请求头字段的名字。管理端前端把令牌放在 `token` 头里，小程序端放在 `authentication` 头里。名字随便起，两端约定一致即可。
- 开发环境的 `itcast`/`itheima` 是教学用的弱密钥，生产环境必须换成强随机密钥。

### 3.10 `BaseContext` — 请求内的"临时身份证"

```java
public class BaseContext {
    public static ThreadLocal<Long> threadLocal = new ThreadLocal<>();
    public static void setCurrentId(Long id) { threadLocal.set(id); }
    public static Long getCurrentId() { return threadLocal.get(); }
}
```

- 拦截器验签后把 `empId/userId` 放进 `ThreadLocal`；
- 同一次请求的处理链路（拦截器→Controller→Service→Mapper）都在**同一个线程**内执行，任何一层都能取到当前用户 id；
- 请求结束线程归还线程池，`remove()` 清理，避免内存泄漏；
- 项目里 `AutoFillAspect`（`AutoFillAspect.java:54`）就是用 `BaseContext.getCurrentId()` 给 `create_user`/`update_user` 自动填值的。

### 3.11 Redis 键 — 非身份类 key

`RedisConstant.SHOP_STATUS = "SHOP_STATUS"` 是店铺营业状态在 Redis 中的键名（`RedisConstant.java:9`）。它不是身份凭证，只是存储键，但同样要**前后端约定一致**。

### 3.12 微信支付相关的密钥体系（`WeChatProperties` 其他字段）

支付模块（本项目已预置 `WeChatPayUtil`，尚未接入业务）会涉及另一套更复杂的密钥体系：

| 配置项 | 是什么 | 用途 |
|--------|--------|------|
| `mchid` | 微信支付商户号 | 收款商户的账号 |
| `mchSerialNo` | 商户 API 证书序列号 | 告诉微信"用哪张证书签名" |
| `privateKeyFilePath` | 商户 API 私钥文件（apiclient_key.pem） | 服务端对请求签名，证明"我是商户" |
| `apiV3Key` | 商户 API v3 密钥 | 解密微信返回的回调数据（如支付结果） |
| `weChatPayCertFilePath` | 微信支付平台证书 | 服务端验证"响应真的来自微信"（验签） |
| `notifyUrl` / `refundNotifyUrl` | 回调地址 | 支付/退款结果异步通知到本服务端 |

**支付双向认证原理**：服务端用「商户私钥」签名请求（商家证明自己是商家）；微信用「平台证书」签名响应，服务端用平台证书验签（服务端确认对方是微信）。`WeChatPayUtil.getClient()`（`WeChatPayUtil.java:52`）用 `WechatPayHttpClientBuilder` 自动完成签名/验签。

---

## 四、容易混淆的点 — 速查对照

```
"登录凭证"   code        ──小程序产生，一次性，交给后端换 openid
"应用身份"   appid       ──小程序账号名，可公开
"应用密码"   secret      ──小程序密钥，仅服务端持有，严禁进前端
"用户身份"   openid      ──用户在当前小程序下的唯一 ID，就是用户表的"账号"
"敏感密钥"   session_key ──解密手机号等，不保存
"跨应用ID"   unionid     ──同一开放平台账号下跨应用统一
"服务端令牌" access_token ──调微信服务端接口用，2小时刷新
"认证令牌"   JWT token   ──自己的服务端签发，请求头携带，验签识别身份
"签名密钥"   secretKey   ──JWT 签名用的密钥，仅服务端持有
"头名称"     token-name  ──请求头放令牌的字段名（token / authentication）
"会话存储"   BaseContext ──ThreadLocal，单次请求内传递当前用户 id
"存储键"     Redis key   ──如 SHOP_STATUS，Redis 中的数据键名
```

**记忆口诀**：
- `code` 换 `openid`（登录时，一次性）；
- `openid` 当账号（存库，长期）；
- `userId` 进 JWT（每次请求验证身份）；
- `appid/secret/secretKey/商户私钥` 是四把"钥匙"，**只在服务端**。

---

## 五、安全注意事项（务必阅读）

1. **secret 泄露 = 小程序失守**：`application-dev.yml` 中配置了真实 appid/secret，且已进入 git 历史，请勿将仓库公开；生产环境用环境变量或配置中心注入，不用明文 yml。
2. **JWT 密钥泄露 = 可伪造任意用户身份**：生产环境必须用随机强密钥，并定期轮换。
3. **JWT 过期时间**：2 小时相对较长；生产环境建议缩短并配合"刷新令牌"机制。
4. **openid 不能当密码用**：任何人拿到 openid 就能模拟该用户（本项目场景下 openid 本身只在服务端内部流转，不暴露给客户端，风险可控）。
5. **code 一次性**：不要缓存 code 重复使用，微信服务器会拒绝。
6. **不要向前端暴露 payload 中的敏感数据**：JWT 的 payload 只是 base64 编码，任何人可以解码看到内容。

---

## 六、常见问题 Q&A

**Q1：小程序每次请求都要登录吗？**
不需要。只有第一次要 `wx.login()` 换 code。登录成功后客户端保存 JWT，后续请求带 `authentication` 头即可；token 过期后需要重新走登录流程（或刷新令牌）。

**Q2：为什么不直接用小程序的 openid 当请求令牌？**
openid 只是"标识"不是"凭证"。服务端无法验证"声称自己是 openid=X 的人真的就是 X"。JWT 的签名保证令牌是服务端签发的、内容未被篡改、且有有效期。

**Q3：为什么用户表没有密码字段？**
因为微信端根本没有"账号密码"概念，身份验证完全由微信完成（微信已替我们验证了用户身份），后端只认 openid。这是 OAuth 思想——**把认证委托给第三方平台**。

**Q4：多台服务器部署，JWT 需要同步吗？**
不需要。JWT 验签只需密钥，任何一台服务器用同一个 secretKey 都能独立完成验证，这就是"无状态认证"对集群最大的优势。

**Q5：用户端和管理端为什么用两套 JWT 密钥？**
权限隔离。管理员 token 即便泄露也无法冒充普通用户调用 `/user/**` 接口，反之亦然；也方便两端独立轮换密钥。

**Q6：`BaseContext` 的值会串线吗（用户 A 看到用户 B 的数据）？**
不会。`ThreadLocal` 是线程私有变量；Tomcat 线程池中一个线程同一时刻只处理一个请求，请求结束 `remove()` 清理。只要保证清理逻辑执行，就不会串号。
