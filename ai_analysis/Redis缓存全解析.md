# Redis 缓存全解析 — 店铺状态 / 菜品 / 套餐

## 一、概览

本项目共有三处缓存，但它们**不是用同一套机制实现的**——这是理解全部代码的第一关键：

| # | 缓存对象 | 实现机制 | 触发方式 | 代码位置 |
|---|---------|---------|---------|---------|
| 1 | 店铺营业状态 | **手写 `RedisTemplate`** | 命令式，显式读写 | `ShopStatusServiceImpl` |
| 2 | 菜品数据 | **Spring Cache 注解** | 声明式，AOP 拦截 | `@Cacheable` / `@CacheEvict` |
| 3 | 套餐数据 | **Spring Cache 注解** | 声明式，AOP 拦截 | `@Cacheable` / `@CacheEvict` |

> **为什么要分两套？**
> 店铺状态只有一个值（营业/打烊），且读写逻辑带校验和兜底，用 `RedisTemplate` 直接操作最直观。
> 菜品和套餐是"按分类 ID 缓存列表"，是典型的 **Cache-Aside（旁路缓存）** 模式，用 Spring Cache 注解可以把"查缓存 / 写缓存 / 清缓存"的样板代码全部省掉。

**缓存全景：**

| 缓存名 | Redis Key 形态 | 值类型 | 序列化方式 | 过期策略 |
|--------|---------------|--------|-----------|---------|
| 店铺状态 | `SHOP_STATUS`（裸键，无前缀） | `Integer` | JDK 序列化 | 无 TTL，永久 |
| 菜品 | `dishCache::<categoryId>` | `Result<List<DishVO>>` | JDK 序列化 | 无 TTL，永久 |
| 套餐 | `setmealCache::<categoryId>` | `Result<List<Setmeal>>` | JDK 序列化 | 无 TTL，永久 |

> ⚠️ **三处缓存都没有设置过期时间**，只能靠显式删除失效。一旦某条失效路径漏写，脏数据会永久留存。

---

## 二、基础设施 — 缓存能跑起来的前提

### 2.1 依赖（`sky-server/pom.xml`）

```xml
<dependency>
    <artifactId>spring-boot-starter-data-redis</artifactId>  <!-- Redis 客户端 + RedisTemplate 自动配置 -->
</dependency>
<dependency>
    <artifactId>spring-boot-starter-cache</artifactId>       <!-- Spring Cache 抽象 + @Cacheable 支持 -->
</dependency>
```

两个依赖缺一不可：**前者提供 Redis 连接，后者提供注解解析**。只加 data-redis 而加 starter-cache，`@Cacheable` 会被完全忽略。

### 2.2 开启注解缓存（`SkyApplication.java`）

```java
@SpringBootApplication
@EnableTransactionManagement
@Slf4j
@EnableCaching          // ← 第 13 行，必需的开关
@EnableScheduling
public class SkyApplication { ... }
```

> **最容易踩的坑**：`@EnableCaching` 缺失时，`@Cacheable` / `@CacheEvict` **不会报错，也不会生效**——缓存静默失效，请求每次照常查库。排查缓存"没生效"时，第一个要确认的就是这个注解。

### 2.3 RedisTemplate 配置（`RedisConfiguration.java`）

```java
@Configuration
@Slf4j
public class RedisConfiguration {

    @Bean
    public RedisTemplate redisTemplate(RedisConnectionFactory redisconnecttionfactory) {
        log.info("开始初始化RedisTemplate");
        RedisTemplate redisTemplate = new RedisTemplate();
        //设置redis的连接工厂对象
        redisTemplate.setConnectionFactory(redisconnecttionfactory);
        //设置redis key的序列化器
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        return redisTemplate;
    }
}
```

**逐行含义：**

| 行 | 作用 | 不这么写会怎样 |
|----|------|---------------|
| `setConnectionFactory(...)` | 绑定连接工厂，否则无法连接 | 抛异常，Bean 创建失败 |
| `setKeySerializer(new StringRedisSerializer())` | key 以纯字符串写入 | 默认 JDK 序列化，key 变成 `\xac\xed...` 二进制 |

> ⚠️ **重要细节**：这里只设置了 **key** 的序列化器，**没有设置 value 的序列化器**。
> `new RedisTemplate()` 的默认 value 序列化器是 `JdkSerializationRedisSerializer`，因此店铺状态的值在 redis-cli 里看到的是**二进制乱码**，不是可读的 `1`。
>
> 若要值也可读，需补一行 `redisTemplate.setValueSerializer(new StringRedisSerializer());`。

> 🔍 **另一个关键点**：这个 `RedisTemplate` Bean **只服务于缓存 #1（店铺状态）**。
> 菜品和套餐用的 Spring Cache 走的是 Spring Boot 自动配置的 `RedisCacheManager`，它**不会读取你定义的 RedisTemplate**，而是自建一套序列化配置。所以这里的 `StringRedisSerializer` 设置对 `@Cacheable` 没有任何影响。

### 2.4 Redis 连接参数（`application.yml` + `application-dev.yml`）

```yaml
# application.yml —— 占位符引用
spring:
  redis:
    host: ${sky.redis.host}
    port: ${sky.redis.port}
    password: ${sky.redis.password}
    database: ${sky.redis.database}
```

```yaml
# application-dev.yml —— 实际值
sky:
  redis:
    host: localhost
    port: 6379
    password: 123456
    database: 10          # ← 注意：用的是 10 号库，不是默认的 0 号库
```

> ⚠️ 排查缓存问题时，`redis-cli` 必须带 `-n 10` 或先 `SELECT 10`，否则在 0 号库里 `KEYS *` 什么都看不到，会误判成"缓存没写进去"。

### 2.5 缓存类型如何被选中

本项目**没有配置 `spring.cache.type`**。Spring Boot 的 `CacheAutoConfiguration` 按以下顺序探测：classpath 上存在 `spring-boot-starter-data-redis` 且容器中有 `RedisConnectionFactory` → 选中 `RedisCacheManager`。因此 `@Cacheable` 自动落到 Redis 上。

---

## 三、缓存 #1：店铺营业状态

### 3.1 涉及代码

**Service 接口** — `ShopStatusService.java`

```java
public interface ShopStatusService {
    void setStatus(Integer status);
    Integer getStatus();
}
```

**Service 实现** — `ShopStatusServiceImpl.java`（唯一直接使用 RedisTemplate 的类）

```java
@Service
@Slf4j
public class ShopStatusServiceImpl implements ShopStatusService {

    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 设置店铺营业状态
     */
    @Override
    public void setStatus(Integer status) {
        //校验参数，仅允许 1（营业）或 0（打烊）
        if (!StatusConstant.ENABLE.equals(status) && !StatusConstant.DISABLE.equals(status)) {
            throw new ShopStatusInvalidException(MessageConstant.SHOP_STATUS_PARAM_ERROR);
        }
        redisTemplate.opsForValue().set(RedisConstant.SHOP_STATUS, status);
    }

    /**
     * 获取店铺营业状态
     */
    @Override
    public Integer getStatus() {
        Integer status = (Integer) redisTemplate.opsForValue().get(RedisConstant.SHOP_STATUS);
        //兜底：Redis中不存在该key（如Redis重启或数据被清空）时，默认营业
        if (status == null) {
            status = StatusConstant.ENABLE;
            redisTemplate.opsForValue().set(RedisConstant.SHOP_STATUS, status);
        }
        return status;
    }
}
```

**常量** — `RedisConstant.java`

```java
public class RedisConstant {
    //店铺营业状态
    public static final String SHOP_STATUS = "SHOP_STATUS";
}
```

**常量** — `StatusConstant.java`

```java
public class StatusConstant {
    public static final Integer ENABLE = 1;   // 启用 / 营业
    public static final Integer DISABLE = 0;  // 禁用 / 打烊
}
```

**管理端 Controller** — `controller/admin/ShopController.java`

```java
@RestController
@RequestMapping("/admin/shop")
public class ShopController {

    @PutMapping("/{status}")                        // PUT /admin/shop/1
    public Result setStatus(@PathVariable Integer status) {
        log.info("设置店铺营业状态为：{}", status == 1 ? "营业" : "打烊");
        shopStatusService.setStatus(status);
        return Result.success();
    }

    @GetMapping("/status")                          // GET /admin/shop/status
    public Result<Integer> getStatus() {
        Integer status = shopStatusService.getStatus();
        return Result.success(status);
    }
}
```

**用户端 Controller** — `controller/user/UserShopController.java`

```java
@RestController
@RequestMapping("/user/shop")
public class UserShopController {

    @GetMapping("/status")                          // GET /user/shop/status
    public Result<Integer> getStatus() {
        Integer status = shopStatusService.getStatus();
        return Result.success(status);
    }
}
```

> **注意**：用户端**只有读接口，没有写接口**。打烊/营业只能由管理端操作，这是合理的权限设计。
> 另外 `/user/shop/status` 在 `WebMvcConfiguration` 中被加入 `excludePathPatterns`，**免 JWT 校验**——小程序首页需要无条件展示营业状态，用户未登录也要能读到。

### 3.2 数据结构

```
Redis (database 10)
└── SHOP_STATUS  →  Integer(1 或 0)   [JDK 序列化的二进制值，无 TTL]
```

调用链非常简单，**全程不碰 MySQL**：

```
                    ┌─────────────────────────────┐
   管理端            │  PUT /admin/shop/{status}   │
                    └──────────────┬──────────────┘
                                   ▼
                    ┌─────────────────────────────┐
                    │ ShopController.setStatus()  │
                    └──────────────┬──────────────┘
                                   ▼
                    ┌─────────────────────────────┐
                    │ 参数校验：仅允许 0 或 1      │
                    │ 非法 → ShopStatusInvalid-   │
                    │        Exception (400)      │
                    └──────────────┬──────────────┘
                                   ▼
                    ┌─────────────────────────────┐
                    │  SET SHOP_STATUS = status   │
                    └──────────────┬──────────────┘
                                   ▼
                         ┌───────────────────┐
                         │   Redis (db 10)   │
                         └─────────┬─────────┘
                                   │
   用户端                          │
   GET /user/shop/status           │
        ▼                          │
   UserShopController.getStatus()  │
        ▼                          │
   ShopStatusServiceImpl.getStatus()│
        ▼                          │
   GET SHOP_STATUS  ◄──────────────┘
        │
        ├── 命中 → 直接返回 Integer
        │
        └── 未命中(null) → 兜底 status = ENABLE(1)
                          → SET SHOP_STATUS = 1   ← 回写
                          → 返回 1
```

### 3.3 两个设计要点

**① 写时的参数校验**

```java
if (!StatusConstant.ENABLE.equals(status) && !StatusConstant.DISABLE.equals(status)) {
    throw new ShopStatusInvalidException(MessageConstant.SHOP_STATUS_PARAM_ERROR);
}
```
`/admin/shop/{status}` 的 `status` 直接来自 URL 路径，用户可能传 `PUT /admin/shop/99`。若不校验，Redis 里会存进 `99`，之后所有 `status == 1 ? 营业 : 打烊` 的判断都把它当"打烊"，静默出错。这里显式拦截，抛 `ShopStatusInvalidException`，由 `GlobalExceptionHandler` 转成错误响应。

> 用 `.equals()` 而非 `==` 是必要的——`ENABLE` 是 `Integer` 包装类型，`==` 比较的是引用，超出 `-128~127` 缓存范围会得到错误结果。

**② 读时的兜底**

```java
if (status == null) {
    status = StatusConstant.ENABLE;
    redisTemplate.opsForValue().set(RedisConstant.SHOP_STATUS, status);
}
```
店铺状态只存在于 Redis（MySQL 中无对应表），因此 Redis 重启或 `FLUSHDB` 后 key 会消失。兜底逻辑保证：**key 不存在时默认"营业"，并立即回写**，避免首次访问返回 `null` 导致前端渲染异常。

> 选择"默认营业"而非"默认打烊"是业务取向：打烊会让小程序首页无法下单，宁可让店先开着。

---

## 四、缓存 #2：菜品数据

### 4.1 涉及代码

**读路径（建缓存）** — `controller/user/DishController.java`

```java
@RestController("userDishController")
@RequestMapping("/user/dish")
public class DishController {
    @Autowired
    private DishService dishService;

    @GetMapping("/list")
    @ApiOperation("根据分类id查询菜品")
    @Cacheable(cacheNames = "dishCache", key = "#categoryId") // 缓存菜品列表
    public Result<List<DishVO>> list(Long categoryId) {
        Dish dish = new Dish();
        dish.setCategoryId(categoryId);
        dish.setStatus(StatusConstant.ENABLE);//查询起售中的菜品

        List<DishVO> list = dishService.listWithFlavor(dish);

        return Result.success(list);
    }
}
```

**写路径（清缓存）** — `controller/admin/DishController.java`

```java
@PostMapping
@CacheEvict(value = "dishCache", key = "#dishDTO.categoryId")   // 精准清一个分类
public Result save(@RequestBody DishDTO dishDTO) { ... }

@DeleteMapping
@CacheEvict(value = "dishCache", allEntries = true)             // 清空整个 dishCache
public Result delete(@RequestParam("ids") List<Long> ids) { ... }

@PutMapping
@CacheEvict(value = "dishCache", allEntries = true)             // 清空整个 dishCache
public Result update(@RequestBody DishDTO dishDTO) { ... }
```

> `page` / `getById` / `list`（管理端）**没有**缓存注解——它们是后台管理查询，数据要求实时准确，且调用频率低，缓存收益为负。

### 4.2 缓存的是什么

```java
@Cacheable(cacheNames = "dishCache", key = "#categoryId")
```

| 要素 | 值 | 说明 |
|------|-----|------|
| `cacheNames` | `"dishCache"` | 缓存区名称，成为 Redis key 前缀 |
| `key` | `#categoryId` | SpEL 表达式，取方法入参 |
| 实际 Redis key | `dishCache::1` | 格式为 `cacheName::key` |
| 缓存的值 | `Result<List<DishVO>>` | **整个返回值对象**，含 `code`/`msg`/`data` |

> ⚠️ 缓存的是 **`Result` 包装对象**而非 `List<DishVO>`。若后续改动返回结构（如新增字段），旧缓存反序列化会失败。这也是 `Result` 必须 `implements Serializable` 的原因（`Result.java:12`）。

**缓存内容的口径**：`dishes WHERE category_id = ? AND status = 1`——**只缓存起售中的菜品**，由 `dish.setStatus(StatusConstant.ENABLE)` 决定。停售菜品既不入库也不入缓存。

### 4.3 读路径数据流（Cache-Aside）

```
用户端：GET /user/dish/list?categoryId=1
        │
        ▼
┌──────────────────────────────────────────────────────────┐
│  @Cacheable 拦截（AOP 代理）                              │
│  计算 key = "dishCache::1"                               │
│  查询 Redis (db 10)                                       │
└────────────────────┬─────────────────────────────────────┘
                     │
        ┌────────────┴────────────┐
        │                         │
   【命中】                   【未命中】
        │                         │
        ▼                         ▼
┌───────────────┐   ┌──────────────────────────────────────┐
│ 直接返回缓存值 │   │ 执行 dishController.list(1) 方法体   │
│ 不执行方法体   │   │   ├─ dish.setCategoryId(1)           │
│ 不走 MySQL     │   │   ├─ dish.setStatus(ENABLE=1)        │
└───────────────┘   │   ├─ dishService.listWithFlavor()    │
                    │   │    ├─ dishMapper.list()          │
                    │   │    │   SELECT * FROM dish       │
                    │   │    │   WHERE category_id = 1    │
                    │   │    │     AND status = 1         │
                    │   │    └─ 逐个菜品查口味            │
                    │   │       SELECT * FROM dish_flavor  │
                    │   │       WHERE dish_id = ?          │
                    │   └─ Result.success(list)            │
                    └───────────────┬──────────────────────┘
                                    ▼
                    ┌──────────────────────────────────────┐
                    │ 方法返回后，把返回值写入 Redis        │
                    │ SET dishCache::1 = <JDK 序列化的     │
                    │                      Result 对象>    │
                    └───────────────┬──────────────────────┘
                                    ▼
                              返回给前端
```

> **性能收益来源**：一次未命中要走 `1 次菜品查询 + N 次口味查询`（N = 菜品数，见 `DishServiceImpl.listWithFlavor` 里的循环）。命中后这些 SQL 全部省掉。

### 4.4 写路径数据流（缓存失效）

```
管理端：POST /admin/dish  (新增菜品, categoryId = 5)
        │
        ▼
┌──────────────────────────────────────────────────────┐
│ @CacheEvict(key = "#dishDTO.categoryId")            │
│ 注意：清缓存在方法执行【之后】进行                    │
└────────────────────┬─────────────────────────────────┘
                     ▼
┌──────────────────────────────────────────────────────┐
│ dishService.saveWithFlavor(dishDTO)                  │
│   ├─ INSERT INTO dish (...)                          │
│   └─ INSERT INTO dish_flavor (...)   ← @Transactional│
└────────────────────┬─────────────────────────────────┘
                     ▼
┌──────────────────────────────────────────────────────┐
│ 方法成功返回 → 触发清缓存                             │
│ DEL dishCache::5                                     │
└────────────────────┬─────────────────────────────────┘
                     ▼
        下次 GET /user/dish/list?categoryId=5
        → 缓存未命中 → 重新查库 → 拿到含新菜品的最新列表
```

> **为什么清缓存在方法之后？** `@CacheEvict` 的 `beforeInvocation` 默认为 `false`。若方法抛异常（如事务回滚），数据库没有实际变化，缓存**不该**被清除——保持旧缓存反而是正确的。反之若设为 `true`，异常时缓存被清空又立刻被回填旧数据，白白浪费一次查库。

### 4.5 三种清缓存策略的差异（重要）

| 操作 | 注解 | 清理范围 | 为什么这么选 |
|------|------|---------|-------------|
| `save` 新增 | `key = "#dishDTO.categoryId"` | 只清该分类 | 新菜品的分类已知，只有该分类的列表变脏，其他分类缓存可保留 |
| `delete` 批量删除 | `allEntries = true` | 清空 `dishCache` | 入参只有 `ids`，需先逐个查库才能得知各自 `category_id`，不划算 |
| `update` 修改 | `allEntries = true` | 清空 `dishCache` | **菜品的分类可以被改**（`category_id` 可编辑）。改分类后，新旧两个分类的缓存**同时**变脏，而精确清除只能拿到新分类 → 必须全清 |

> `update` 用 `allEntries` 不是偷懒，而是正确性要求。若这里也写成 `key = "#dishDTO.categoryId"`，把菜品从"川菜"移到"粤菜"后，**"川菜"分类的缓存会永远残留这道菜**——因为没人再去清它。

---

## 五、缓存 #3：套餐数据

### 5.1 涉及代码

**读路径** — `controller/user/SetmealController.java`

```java
@RestController("userSetmealController")
@RequestMapping("/user/setmeal")
public class SetmealController {
    @Autowired
    private SetmealService setmealService;

    @GetMapping("/list")
    @ApiOperation("根据分类id查询套餐")
    @Cacheable(cacheNames = "setmealCache", key = "#categoryId")
    public Result<List<Setmeal>> list(Long categoryId) {
        Setmeal setmeal = new Setmeal();
        setmeal.setCategoryId(categoryId);
        setmeal.setStatus(StatusConstant.ENABLE);

        List<Setmeal> list = setmealService.list(setmeal);
        return Result.success(list);
    }

    @GetMapping("/dish/{id}")
    @ApiOperation("根据套餐id查询包含的菜品列表")
    public Result<List<DishItemVO>> dishList(@PathVariable("id") Long id) {
        // 注意：此接口【没有】缓存注解
        List<DishItemVO> list = setmealService.getDishItemById(id);
        return Result.success(list);
    }
}
```

**写路径** — `controller/admin/SetmealController.java`

```java
@PostMapping
@CacheEvict(value = "setmealCache", key = "#setmealDTO.categoryId")  // 精准清一个分类
public Result save(@RequestBody SetmealDTO setmealDTO) { ... }

@DeleteMapping
@CacheEvict(value = "setmealCache", allEntries = true)
public Result delete(@RequestParam List<Long> ids) { ... }

@PostMapping("/status/{status}")
@CacheEvict(value = "setmealCache", allEntries = true)
public Result startOrStop(@PathVariable Integer status, Long id) { ... }

@PutMapping
@CacheEvict(value = "setmealCache", allEntries = true)
public Result update(@RequestBody SetmealDTO setmealDTO) { ... }
```

### 5.2 与菜品缓存的对比

| 维度 | 菜品缓存 | 套餐缓存 | 说明 |
|------|---------|---------|------|
| 缓存名 | `dishCache` | `setmealCache` | 相互独立，互不影响 |
| Key 格式 | `dishCache::<categoryId>` | `setmealCache::<categoryId>` | 同构 |
| 缓存值类型 | `Result<List<DishVO>>` | `Result<List<Setmeal>>` | 菜品带口味（`DishVO`），套餐不带关联 |
| 建缓存接口 | `GET /user/dish/list` | `GET /user/setmeal/list` | 均为 C 端浏览接口 |
| 清缓存接口 | save / delete / update | save / delete / **startOrStop** / update | 套餐多一个起售停售 |
| 修改时清空范围 | `allEntries` | `allEntries` | 理由相同：分类可改 |

### 5.3 为什么套餐多一个 `startOrStop` 清缓存

```java
@PostMapping("/status/{status}")
@CacheEvict(value = "setmealCache", allEntries = true)
public Result startOrStop(@PathVariable Integer status, Long id) { ... }
```

套餐有"起售 / 停售"操作（`SetmealServiceImpl.startOrStop`），而缓存只存 `status = 1` 的套餐。因此：

```
套餐停售 (status: 1 → 0)
    → 该套餐应从 C 端列表消失
    → 必须清缓存，否则用户仍能看到已停售的套餐并下单  ← 业务事故

套餐起售 (status: 0 → 1)
    → 该套餐应出现在 C 端列表
    → 必须清缓存，否则用户看不到
```

这里用 `allEntries = true` 而非精确 key：入参只有 `id`，需额外查库才知道 `category_id`；且起售时还要级联校验套餐内菜品是否全部起售（`SetmealEnableFailedException`），逻辑复杂，全清更稳妥。

> **对比**：菜品在本项目中**没有**对应的 `POST /admin/dish/status/{status}` 起售停售接口（已确认 `controller/admin/DishController.java` 中不存在）。
> ⚠️ **如果后续补充该接口，必须同步加上 `@CacheEvict(value = "dishCache", allEntries = true)`**——菜品停售后不清缓存，用户会在 C 端看到已停售的菜并成功下单。

---

## 六、Spring Cache 工作机制

理解这一节，才能解释"为什么注解能生效""什么时候会失效"。

### 6.1 AOP 代理拦截

```
HTTP 请求 → DispatcherServlet
              │
              ▼
        ┌───────────────────────────────┐
        │  DishController 的【代理对象】 │   ← Spring 容器中的 Bean 实际是代理
        │  (CGLIB / JDK 动态代理)        │
        └───────────────┬───────────────┘
                        │
        ① 进入方法前：解析 @Cacheable
           → 计算 key = dishCache::1
           → 查 Redis
           ├─ 命中 → 直接返回，方法体【不执行】
           └─ 未命中 ↓
                        │
        ② 调用【真实对象】的方法体
           → dishService.listWithFlavor(...) → 查 MySQL
                        │
        ③ 方法返回后：把返回值写入 Redis
                        │
        ④ 返回给调用方
```

**由此推出的三个重要结论：**

| 结论 | 说明 |
|------|------|
| **同类内部调用不生效** | `this.list(...)` 绕过代理，注解失效。本项目 `@Cacheable` 都标在 Controller 上、由 Spring MVC 经代理调用，故正常生效。 |
| **注解方法必须是 `public`** | 代理无法拦截 `private` / `protected` 方法。 |
| **被注解的 Bean 不能自己 `new`** | `new DishController()` 得到的不是代理对象，注解无效。 |

### 6.2 Key 的生成规则

| 场景 | Key 表达式 | 入参 `categoryId=3` 时的实际 key |
|------|-----------|-------------------------------|
| 默认（不写 `key`） | 由 `SimpleKeyGenerator` 生成 | `dishCache::SimpleKey []` |
| `@Cacheable(key = "#categoryId")` | SpEL 取参数 | `dishCache::3` |

完整形态：`<cacheName>::<key>`（前缀 `::` 是 Spring 默认的 `computePrefixWith` 规则）。

> `#categoryId` 中的 `#` 是 SpEL 的**变量引用**语法，`categoryId` 必须与方法形参名一致（依赖编译时保留参数名，Maven 需开 `-parameters`，Spring Boot 父 POM 已默认开启）。

### 6.3 序列化：为什么 redis-cli 里是乱码

Spring Boot 自动配置的 `RedisCacheManager` **不使用**你定义的 `RedisTemplate`，它自建序列化配置：

| 部分 | 序列化器 | 在 redis-cli 中的表现 |
|------|---------|---------------------|
| key | `StringRedisSerializer` | ✅ 可读：`dishCache::3` |
| value | `JdkSerializationRedisSerializer` | ❌ 二进制乱码，以 `\xac\xed\x00\x05` 开头 |

`\xac\xed\x00\x05` 是 Java 序列化流的魔数（`STREAM_MAGIC = 0xACED` + `STREAM_VERSION = 5`）。

> 若要缓存值也可读（如 JSON），需显式定义 `RedisCacheManager` Bean 并配置 `RedisCacheConfiguration.serializeValuesWith(...)`。本项目未配置，故用默认的 JDK 序列化。

### 6.4 过期时间

本项目**未配置** `spring.cache.redis.time-to-live`，因此缓存条目**不带过期时间**，只依赖 `@CacheEvict` 显式清除。

后果：任何一条失效路径漏写，脏数据就**永久**留在 Redis 里。这也是"新增菜品起售停售接口时必须补 `@CacheEvict`"这一提醒的根本原因。

---

## 七、三种缓存汇总对比

| 维度 | 店铺营业状态 | 菜品数据 | 套餐数据 |
|------|-------------|---------|---------|
| 实现方式 | 手写 `RedisTemplate` | Spring Cache 注解 | Spring Cache 注解 |
| Redis Key | `SHOP_STATUS` | `dishCache::<categoryId>` | `setmealCache::<categoryId>` |
| 值 | `Integer` | `Result<List<DishVO>>` | `Result<List<Setmeal>>` |
| 读接口 | `GET /admin/shop/status`<br>`GET /user/shop/status` | `GET /user/dish/list` | `GET /user/setmeal/list` |
| 写接口 | `PUT /admin/shop/{status}` | `POST/DELETE/PUT /admin/dish` | `POST/DELETE/PUT /admin/setmeal`<br>`POST /admin/setmeal/status/{status}` |
| 失效方式 | 写入时覆盖 | 写操作后清除 | 写操作后清除 |
| 兜底逻辑 | ✅ key 不存在时默认营业并回写 | ❌ 无（未命中即查库回填） | ❌ 无 |
| 参数校验 | ✅ 仅允许 0/1 | ❌ 无 | ❌ 无 |
| 过期时间 | 无 | 无 | 无 |
| 数据源 | 仅 Redis（MySQL 无表） | MySQL | MySQL |

---

## 八、全量数据流全景图

```
┌───────────────────────────────────────────────────────────────────────────────┐
│                              浏览器 / 小程序                                    │
└───────┬───────────────────────────────────────────────────────┬───────────────┘
        │ 管理端                                                 │ 用户端
        │                                                        │
        │ PUT /admin/shop/1                                      │ GET /user/shop/status
        │ POST/PUT/DELETE /admin/dish                            │ GET /user/dish/list?categoryId=1
        │ POST/PUT/DELETE /admin/setmeal                         │ GET /user/setmeal/list?categoryId=2
        ▼                                                        ▼
┌───────────────────────┐                        ┌──────────────────────────────┐
│ JwtTokenAdminInterceptor│                       │ JwtTokenUserInterceptor      │
│ addPathPatterns        │                       │ addPathPatterns("/user/**")  │
│   ("/admin/**")        │                       │ 排除：/user/user/login       │
│ 排除：/admin/employee/  │                       │      /user/shop/status ← ★  │
│       login            │                       │      /user/dish/list ★ 无排除 │
└───────────┬───────────┘                        └──────────────┬───────────────┘
            │                                                    │
            ▼                                                    ▼
┌───────────────────────────────┐              ┌──────────────────────────────────────┐
│  ShopController / DishController│            │ DishController / SetmealController   │
│  / SetmealController            │            │ / UserShopController                 │
│  （管理端：写入 + 清缓存）        │            │ （用户端：读取 + 建缓存）              │
└───────┬─────────────────────────┘            └──────────────┬───────────────────────┘
        │                                                     │
        │ ① 先执行方法体（写 MySQL）                          │ @Cacheable 先查 Redis
        │ ② 方法成功后再清 Redis                              │   ├─ 命中 → 直接返回（不走 MySQL）
        │                                                     │   └─ 未命中 → 查 MySQL → 回填 Redis
        ▼                                                     ▼
┌───────────────────────┐                      ┌────────────────────────────────────┐
│  ShopStatusServiceImpl │                      │  DishServiceImpl.listWithFlavor()  │
│  （唯一用 RedisTemplate）│                     │  SetmealServiceImpl.list()         │
└───────────┬───────────┘                      └────────────────┬───────────────────┘
            │                                                    │
            │                                    ┌───────────────┴──────────────┐
            │                                    ▼                              ▼
            │                          ┌──────────────────┐          ┌──────────────────┐
            │                          │ DishMapper / XML │          │SetmealMapper / XML│
            │                          │ DishFlavorMapper │          │                  │
            │                          └────────┬─────────┘          └────────┬─────────┘
            │                                   │                            │
            │                                   ▼                            ▼
            │                          ┌──────────────────────────────────────────────┐
            │                          │        MySQL : sky_take_out                 │
            │                          │        dish / dish_flavor / setmeal         │
            │                          └──────────────────────────────────────────────┘
            │
            ▼
┌───────────────────────────────────────────────────────────────────────────────┐
│                          Redis  (localhost:6379, database 10)                  │
│                                                                               │
│  SHOP_STATUS        → Integer        [裸键，RedisTemplate 直写]                │
│  dishCache::1       → Result<List<DishVO>>    [Spring Cache 管理]             │
│  dishCache::5       → Result<List<DishVO>>                                    │
│  setmealCache::2    → Result<List<Setmeal>>   [Spring Cache 管理]             │
│  ...                                                                          │
│                                                                               │
│  ⚠️ 全部无 TTL（永不过期）                                                     │
│  ⚠️ value 均为 JDK 序列化二进制                                                │
└───────────────────────────────────────────────────────────────────────────────┘
```

---

## 九、关键设计要点与易错点

### 9.1 缓存一致性：三种清缓存策略

| 策略 | 写法 | 适用场景 |
|------|------|---------|
| 精确清除 | `key = "#dto.categoryId"` | 能确定受影响的分类，且分类不会变（新增） |
| 全量清除 | `allEntries = true` | 受影响范围不确定（修改，分类可变）或入参信息不足（批量删除） |
| 全库清除 | `cacheNames = "dishCache"` 不加 key | 项目未使用 |

> **原则**：宁可多清，不可漏清。全量清除只是牺牲一点命中率（下次访问重新查库），漏清则会导致脏数据永久残留。

### 9.2 已识别的缺口

| 缺口 | 影响 | 建议 |
|------|------|------|
| 菜品缺少起售/停售接口 | 功能缺失，非缓存 bug | 补充时必须加 `@CacheEvict(allEntries = true)` |
| 所有缓存无 TTL | 漏清即永久脏数据 | 可配置 `spring.cache.redis.time-to-live` 作为兜底 |
| 无缓存穿透保护 | 不存在的 `categoryId` 每次都查库 | `@Cacheable` 默认缓存 null（`cacheNullValues=true`），已有缓解 |
| 无缓存雪崩保护 | 同时大量 key 失效会压垮 MySQL | 加 TTL 时设置随机抖动 |
| `SHOP_STATUS` 强依赖 Redis | Redis 不可用时接口 500 | 已有 key 缺失兜底，但连接失败仍会抛异常 |

### 9.3 排查缓存问题的方法

```bash
# 连接（注意 -n 10，必须指定 10 号库）
redis-cli -h localhost -p 6379 -a 123456 -n 10

# 1. 查看所有缓存 key
KEYS *
# 预期：SHOP_STATUS、dishCache::1、setmealCache::2 等
# 注意：可能还会看到 dishCache~keys 之类的辅助键，属 Spring Cache 内部使用

# 2. 查看值的类型（string / zset / ...）
TYPE dishCache::1
# 预期：string

# 3. 查看店铺状态（value 是 JDK 序列化二进制，看到 \xac\xed 开头即为正常）
GET SHOP_STATUS

# 4. 确认是否真的没有过期时间（-1 = 永不过期，-2 = key 不存在）
TTL dishCache::1
TTL SHOP_STATUS

# 5. 手动清缓存用于复现测试
DEL dishCache::1
FLUSHDB            # ⚠️ 清空整个 10 号库，慎用
```

### 9.4 验证缓存是否生效的实验步骤

```
实验：确认 /user/dish/list 走了缓存

① 清空缓存
   redis-cli -n 10 DEL dishCache::1

② 第一次请求
   GET /user/dish/list?categoryId=1
   → 观察后端日志：出现 dish / dish_flavor 的 SQL（MyBatis 日志）
   → redis-cli: EXISTS dishCache::1  → 1（已写入）

③ 第二次请求（相同 categoryId）
   GET /user/dish/list?categoryId=1
   → 【不应】再出现任何 SQL 日志 ← 这就是缓存命中的证据
   → 返回内容与第一次完全一致

④ 触发清缓存
   POST /admin/dish  (新增一个 categoryId=1 的菜品)
   → redis-cli: EXISTS dishCache::1  → 0（已被 @CacheEvict 清除）

⑤ 再次请求
   GET /user/dish/list?categoryId=1
   → SQL 日志重新出现，且返回结果包含新增的菜品 ✅
```

### 9.5 JDK 序列化的隐患

由于缓存值使用 JDK 序列化，存在一个隐蔽风险：

```
① 缓存中已存在 dishCache::1（基于旧版 DishVO 序列化）
② 你给 DishVO 新增了一个字段，或修改了 serialVersionUID
③ 下次请求命中该缓存 → 反序列化失败
   → InvalidClassException / ClassNotFoundException
```

**规避方式**：修改任何被缓存的 POJO（`Result`、`DishVO`、`DishFlavor`、`Setmeal` 等）后，**清空对应缓存**（`DEL dishCache::*` 或 `FLUSHDB`）。

> 相关 POJO 均已 `implements Serializable`（已确认：`Result`、`DishVO`、`DishFlavor`、`Setmeal`、`SetmealVO`、`DishItemVO` 等），这是 JDK 序列化的前提。

### 9.6 两套缓存机制不可混用

```
❌ 错误认知：RedisConfiguration 里设置了 StringRedisSerializer，
           所以 @Cacheable 的 key/value 也走这个序列化器
✅ 实际：@Cacheable 走自动配置的 RedisCacheManager，与自定义 RedisTemplate 无关

后果：在 RedisConfiguration 里给 RedisTemplate 加配置，
     对 dishCache / setmealCache 完全不生效，只影响 SHOP_STATUS
```

若要让 `@Cacheable` 的值序列化成 JSON，必须**单独定义** `RedisCacheManager` Bean：

```java
@Bean
public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
    RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    new GenericJackson2JsonRedisSerializer()));
    return RedisCacheManager.builder(factory).cacheDefaults(config).build();
}
```

---

## 十、涉及文件清单

| 文件 | 路径 | 角色 |
|------|------|------|
| `RedisConfiguration.java` | `sky-server/.../config/` | RedisTemplate Bean（仅服务店铺状态缓存） |
| `SkyApplication.java` | `sky-server/.../` | `@EnableCaching` 开启注解缓存 |
| `RedisConstant.java` | `sky-common/.../constant/` | Redis key 常量（`SHOP_STATUS`） |
| `StatusConstant.java` | `sky-common/.../constant/` | 状态常量（`ENABLE=1` / `DISABLE=0`） |
| `ShopStatusService.java` | `sky-server/.../service/` | 店铺状态业务接口 |
| `ShopStatusServiceImpl.java` | `sky-server/.../service/impl/` | **唯一直接使用 RedisTemplate 的类** |
| `controller/admin/ShopController.java` | `sky-server/.../controller/admin/` | 管理端：设置 / 读取营业状态 |
| `controller/user/UserShopController.java` | `sky-server/.../controller/user/` | 用户端：只读营业状态（免 JWT） |
| `controller/user/DishController.java` | `sky-server/.../controller/user/` | **`@Cacheable` 建菜品缓存** |
| `controller/admin/DishController.java` | `sky-server/.../controller/admin/` | **`@CacheEvict` 清菜品缓存** |
| `controller/user/SetmealController.java` | `sky-server/.../controller/user/` | **`@Cacheable` 建套餐缓存** |
| `controller/admin/SetmealController.java` | `sky-server/.../controller/admin/` | **`@CacheEvict` 清套餐缓存** |
| `DishServiceImpl.java` | `sky-server/.../service/impl/` | 缓存未命中时查库（菜品 + 口味） |
| `SetmealServiceImpl.java` | `sky-server/.../service/impl/` | 缓存未命中时查库；`startOrStop` 触发清缓存 |
| `DishMapper.xml` | `sky-server/.../resources/mapper/` | 菜品动态 SQL |
| `SetmealMapper.xml` | `sky-server/.../resources/mapper/` | 套餐动态 SQL |
| `Result.java` | `sky-common/.../result/` | 被缓存的包装类（必须 Serializable） |
| `WebMvcConfiguration.java` | `sky-server/.../config/` | 拦截器路径配置（`/user/shop/status` 免 JWT） |
| `application.yml` / `application-dev.yml` | `sky-server/.../resources/` | Redis 连接参数（db 10） |
