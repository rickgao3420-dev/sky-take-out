# 为什么使用 `updateSnapshot` 和 `updateExpected`

整理日期：2026-10-02。

本文解释项目里新增的 `updateSnapshot`、`updateExpected` 两类 Mapper 方法的设计意图，并澄清：这不是把所有通用 `update` 一律改名，而是为不同业务语义和并发要求提供更明确的更新入口。

## 先给结论

方法名带上业务含义，是为了让代码读者知道“这条更新允许改哪些东西、必须满足什么条件”：

- `updateSnapshot`：更新购物车行的数量和商品快照字段，并要求购物车行属于指定用户。
- `updateExpected`：订单状态仍等于调用方预期的旧状态时，才将订单更新到新状态。
- `payIfPending`：支付的专用条件更新，还附带用户归属、未支付和有效期条件。

通用 `update` 的名字只说“更新”，看不出这些约束。高风险业务显式写出约束，可以减少误用，也便于从调用点审查业务规则。

## 1. `updateSnapshot`：购物车的整组快照更新

当前 SQL 的作用范围是：

```sql
UPDATE shopping_cart
SET number = ?,
    amount = ?,
    name = ?,
    image = ?,
    dish_flavor = ?
WHERE id = ?
  AND user_id = ?;
```

它由 `ShoppingCartServiceImpl.merge` 调用：合并相同 SKU 时，保留一条购物车记录，把新数量以及经过当前商品校验的名称、图片、价格、口味写回；如果旧数据中有多个重复 SKU，再删除多余记录。

选择 `updateSnapshot` 这个名字有两层意思：

1. **限定业务字段**：这里更新的是购物车项的商品展示/价格快照和数量，不是任意购物车字段的通用修改。
2. **标明写入来源**：快照值应由服务器根据当前菜品/套餐重新装配，而不是直接信任客户端提交的名称、价格、图片。

它的 `WHERE` 除了 `id` 还带 `user_id`，将归属检查落到更新语句本身。即使某处错误地传入别人的购物车行 ID，只要 userId 不匹配，就不会更新那一行。对应实现见 [ShoppingCartMapper.java](../sky-server/src/main/java/com/sky/mapper/ShoppingCartMapper.java)。

购物车减一只需要改变数量，所以仍保留更窄的 `updateNumberById`。这样不会为了减数量，把对象里可能过期的价格或名称也写回去。也就是说，方法按更新语义区分，不是为了凑更多方法名。

## 2. `updateExpected`：订单状态比较后更新

订单的支付、取消、接单、拒绝、配送、完成和定时任务可能在时间上相互竞争。服务通常先读订单，判断当前状态允许操作；但“读状态”和“写新状态”之间可能有另一个请求先改变状态。

`updateExpected` 的 SQL 核心是：

```sql
UPDATE orders
SET ...需要写入的非空字段...
WHERE id = :orderId
  AND status = :expectedStatus;
```

例如取消流程读到订单状态为“待接单”（假设值 2），准备写成“已取消”（值 6），更新条件会是：

```sql
UPDATE orders
SET status = 6, ...
WHERE id = 123 AND status = 2;
```

数据库把条件判断和这条更新作为一个原子语句执行。若另一请求已经把状态从 2 改成 3，这条 SQL 就不匹配，影响行数为 0；调用方不应继续把取消报告为成功。

Mapper 方法返回 `int`，服务一般要求结果等于 1：

- `1`：数据库里当时仍是预期状态，更新成功。
- `0`：记录不存在，或状态已被其他操作改变；当前请求基于旧状态的决定失效。

这就是“比较并交换”（compare-and-set）的思路：只有旧值符合预期，才写入新值。SQL 定义见 [OrderMapper.xml](../sky-server/src/main/resources/mapper/OrderMapper.xml)，主要调用见 [OrderServiceImpl.java](../sky-server/src/main/java/com/sky/service/impl/OrderServiceImpl.java) 和 [UserOrderService.java](../sky-server/src/main/java/com/sky/service/UserOrderService.java)。

## 3. 为什么不只在 Java 里先判断，再调用通用 `update`

下面的逻辑有并发窗口：

```java
Orders row = mapper.getById(id);
if (row.getStatus() == PENDING) {
    mapper.update(Orders.builder().id(id).status(CANCELLED).build());
}
```

时间线可能是：

```text
取消请求：读到待付款
支付请求：读到待付款
支付请求：无条件写成待接单并提交
取消请求：无条件写成已取消并提交
```

最后取消覆盖支付，虽然取消请求做检查时确实看到过“待付款”。问题在于检查和写入是两条独立操作，中间状态可能改变。

改成 `updateExpected(order, PENDING)` 后，数据库在执行 UPDATE 时再次核对状态。若支付先改成待接单，取消 SQL 的 `WHERE status = PENDING` 会匹配 0 行，取消请求无法覆盖支付结果。

因此 Java 层的状态判断仍有价值：它能给用户更清楚的业务提示、避免明显不合法的 SQL 尝试；但并发正确性依赖最终 SQL 中的旧状态条件，而不能只依赖先前读出来的对象。

## 4. 支付为什么另有 `payIfPending`

支付条件比“状态等于某值”更具体。当前专用 SQL 一次检查：

```sql
WHERE id = :id
  AND user_id = :userId
  AND status = 1           -- 待付款
  AND pay_status = 0        -- 未支付
  AND timestampadd(MINUTE, 15, order_time) > :now
```

同时将状态改为待接单、支付状态改为已支付，并写结账时间和支付方式。

它把支付所需的几条约束放在一次原子更新里：订单属于当前用户、仍待付款、尚未支付且未超过支付有效期。若取消或超时任务先完成，支付更新影响 0 行；若支付先成功，取消/超时的预期旧状态也不再匹配。

这也是为什么没有把所有场景粗暴塞进一个 `updateExpected`：通用状态流转适用于多种订单动作；支付还有额外条件，单独命名和实现更容易审计。

## 5. 为什么保留/增加多个方法，而不是做一个万能 `update`

万能更新往往有一个弱点：从调用点看不清楚保护条件和允许修改字段。业务代码传一个 `Orders` 对象进去，实际 SQL 可能只按 ID 更新非空字段；若没有旧状态条件，就可能覆盖新状态；若没有 user_id 条件，也可能缺少归属限制。

专用方法把关键语义放到接口上：

| 方法 | 更新对象 | 必要条件 | 影响行数的含义 |
| --- | --- | --- | --- |
| `updateNumberById` | 购物车数量 | 购物车行 ID | 主要用于简单减一 |
| `updateSnapshot` | 购物车数量和商品快照 | 行 ID + 用户 ID | 当前签名未返回行数，专用于购物车合并 |
| `updateExpected` | 订单状态及本次需要的字段 | 订单 ID + 预期旧状态 | `1` 成功；`0` 代表状态竞争/记录不匹配 |
| `payIfPending` | 支付状态转换 | ID + 用户 ID + 待付款 + 未支付 + 未过期 | `1` 支付状态转换成功；否则不能按当前条件支付 |
| 通用 `update` | 由 Orders 非空字段动态决定 | 通常只有订单 ID | 不应在需要防止状态覆盖的流程中替代条件更新 |

专用方法通常更适合关键写路径；通用方法仍可用于不需要这些并发条件的普通更新或兼容逻辑。当前重构没有因为新增两个方法就意味着“所有 update 都应换成专用方法”。

## 6. 命名的作用：让意图可见、限制误用

看到下面的调用：

```java
orderMapper.updateExpected(update, oldStatus);
```

读者能立即追问/确认：“`oldStatus` 是什么？状态不匹配时服务是否处理 0 行？”

而看到：

```java
shoppingCartMapper.updateSnapshot(row);
```

读者会知道这是更新一组购物车快照数据，不只是盲目地把一个 Java 对象写回数据库。

显式名称不能替代 SQL、事务或调用方检查，但可以把隐含的业务契约写到接口上，让代码审查、测试和后续维护更有抓手。

## 7. 这几种 update 也各有边界

- `updateExpected` 按当前 SQL 只比较订单 `status`。它不自动比较 `pay_status` 或所有字段；需要额外条件的转换应像支付那样写专用 SQL，或扩展条件。
- `updateExpected` 的影响行数必须检查。只传入旧状态但忽略结果，冲突请求仍可能被错误报告成成功。
- `updateSnapshot` 当前声明返回 `void`，因此调用方不能通过返回值确认到底更新了几行；它有 `user_id` 防护，并依赖前面的用户锁和用户范围查询。若未来需要严格确认更新成功，可考虑改为返回影响行数并要求为 1。
- 条件更新保护一条 SQL 的状态转换，不会让多条表操作自动成为一个整体；跨表写入仍需要事务。
- 方法名不是安全边界本身。真正保证来自执行的 SQL 条件、正确传参、事务范围和调用方检查。

## 总结

新增专用更新方法的意图是把关键业务约束从“调用者记得遵守”前移到 Mapper API 和 SQL：`updateSnapshot` 明确购物车快照更新并限定用户归属；`updateExpected` 用旧状态条件阻止过期操作覆盖订单新状态；`payIfPending` 则把支付的归属、状态和时效规则合并进单条原子更新。

所以这不是为了把项目里的 `update` 越拆越多，而是把不同风险、不同字段范围、不同成功判据的写操作区分开。简单数量调整继续用数量更新方法；涉及订单状态竞争的流程使用条件更新。
