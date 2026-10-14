# `UserWriteLock.lock()` 方法作用说明

整理日期：2026-09-24。

本文回答：`UserWriteLock.java` 的 `lock()` 有什么作用？为什么不在业务代码里只调用 `BaseContext.getCurrentId()`？

## 先看代码

项目中的方法逻辑很短：

```java
public Long lock() {
    Long id = BaseContext.getCurrentId();
    if (id == null || userMapper.lockById(id) == null) {
        throw new ShoppingCartBusinessException("用户不存在，请重新登录");
    }
    return id;
}
```

`userMapper.lockById(id)` 执行的 SQL 是：

```sql
SELECT id FROM user WHERE id = ? FOR UPDATE;
```

源码位置：

- [UserWriteLock.java](../sky-server/src/main/java/com/sky/service/support/UserWriteLock.java)
- [UserMapper.java](../sky-server/src/main/java/com/sky/mapper/UserMapper.java)

## 两种调用的区别

```java
Long id = BaseContext.getCurrentId();
```

这句话只回答：**当前请求对应哪个用户 ID？**它从请求处理期间的上下文中取出身份。它不会访问数据库，不会确认这条用户记录仍然存在，也不会获得数据库锁。

```java
Long id = userWriteLock.lock();
```

这句话回答：**当前用户是谁，并且我是否已经为这个用户的写流程取得排队锁？**它依次做三件事：

1. 从 `BaseContext` 取当前用户 ID。
2. 用 `SELECT ... FOR UPDATE` 尝试锁住该用户的 `user` 表记录；如果另一个事务持有冲突锁，就在这里等待。
3. 如果上下文里没有 ID，或数据库找不到该用户记录，就抛出业务异常；成功时把 ID 返回给后面的购物车或业务查询使用。

因此 `lock()` 不是单纯的 ID getter，而是一个带数据库副作用的“取得当前用户写锁并返回用户 ID”的操作。

## 为什么要多这一层

### 1. 只取 ID 不会协调并发

假设两个下单请求都写成：

```java
Long userId = BaseContext.getCurrentId();
List<ShoppingCart> rows = carts.listByUserId(userId);
```

两边都能马上取到同一个 ID，然后同时读购物车。`BaseContext` 通常只是请求线程里的身份上下文，它不是数据库互斥锁；这段代码不会让第二个请求等待，也就可能让两个事务都按同一份购物车创建订单。

改为：

```java
Long userId = userWriteLock.lock();
List<ShoppingCart> rows = carts.listByUserId(userId);
```

两个事务会先争同一条 `user` 记录上的行锁。第二个事务在 `lock()` 里等待，等第一个下单事务提交、购物车清空后才继续读取购物车。

### 2. 把“锁定当前用户”的协议集中定义

项目里需要排队的入口不只有下单，还有加购、减购、清空购物车、再来一单等。若每个方法都自己写：

```java
Long userId = BaseContext.getCurrentId();
userMapper.lockById(userId);
```

容易出现某个入口漏掉锁、忘记检查空 ID、忽略用户行不存在，或以后锁策略调整时改漏代码。统一调用 `userWriteLock.lock()`，让方法名直接表达意图，也让这些流程共用同一实现和错误处理。

使用示意：

```java
@Transactional
public OrderSubmitVO submit(...) {
    Long userId = userWriteLock.lock(); // 先建立同用户写入的排队点
    List<ShoppingCart> rows = carts.listByUserId(userId);
    // 创建订单、明细并清空购物车
}
```

这里的 `@Transactional` 很关键：调用 `lock()` 本身不会开启事务。调用者需要让取锁、后续查询和写入处于同一个事务里，使数据库锁持续到整个业务提交或回滚。当前下单和购物车写方法都在事务中先调用该方法。

### 3. 避免在业务代码里重复实现技术细节

业务流程真正关心的是“拿到当前用户并把同用户写操作排队”，而不必每处都知道锁是由 `UserMapper` 的哪条 SQL 实现。将它封装到 `UserWriteLock` 后，调用者可以使用清楚的业务意图；SQL 细节留在 Mapper 层，集中策略留在 support 组件里。

这层抽象并非为了让代码层数越多越好。它的价值在于封装了重复的、容易漏掉的并发协议。如果一个地方只需要读取当前登录用户做归属查询，而且不需要同用户写入排队，直接使用 `BaseContext.getCurrentId()` 仍然合适。

## 它不是做什么的

### `lock()` 不负责登录认证

用户身份通常已由拦截器从 token 校验后写入 `BaseContext`。`lock()` 使用这个 ID，并额外检查 ID 非空以及数据库中能否找到对应用户。身份认证和请求上下文的建立仍由拦截器负责。

### `lock()` 不会自动开启事务

方法上没有 `@Transactional`。锁的持有时间取决于调用它的事务。把它放在事务外使用，锁可能在语句结束后很快释放，无法保护后面的多步操作。

### `lock()` 不会锁住这个用户的所有数据

SQL 只锁 `user` 表里对应的用户记录。购物车行不会因为调用了这个方法就被数据库自动锁住。购物车写流程之所以互斥，是因为相关代码约定都先取同一用户行锁；跳过 `userWriteLock.lock()` 的其他写入不会自动受它约束。

### `lock()` 不是所有用户操作都必须调用

需要与购物车、下单等流程共享排队点的写操作应调用它。只读查询通常不需要这把写锁。订单支付/取消等状态竞争在本项目主要依赖带旧状态条件的 SQL 更新，因此具体方法会按自己的并发保护方式实现，不是机械地给每个用户接口都加锁。

## 与直接取 ID 的选择对照

| 场景 | 适合的调用 | 原因 |
| --- | --- | --- |
| 只想知道当前用户是谁，进行普通读取或归属过滤 | `BaseContext.getCurrentId()` | 只需要身份，不需要锁定写流程 |
| 修改/消费购物车，需要与同用户其他购物车写操作排队 | `userWriteLock.lock()` | 取 ID 的同时对用户行加锁，并统一验证用户记录 |
| 创建订单并消费购物车 | `userWriteLock.lock()` | 先排队，再在同一事务内读车、建单、清车 |
| 用预期订单状态做支付/取消更新 | 按该操作的状态条件更新实现 | 由原子条件更新处理订单状态竞争，不必因此认为所有场景都要用户锁 |

## 总结

`BaseContext.getCurrentId()` 只提供“当前是谁”；`UserWriteLock.lock()` 则把“当前是谁”与“锁定此用户的写操作排队点”组合起来，并检查用户 ID 和用户记录有效。加上这一层，是为了让购物车和下单等相关写路径统一遵守同一个数据库锁协议，减少漏锁与重复样板代码。

记住调用约定：**要保护某个用户的相关写流程时，在事务内尽早调用 `userWriteLock.lock()`，并把返回的 ID 用于后续业务；只读或采用其他并发控制的场景，不需要为了取用户 ID 而机械加锁。**
