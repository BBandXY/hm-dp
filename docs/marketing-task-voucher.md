# 每日任务—优惠券营销闭环

## 业务链路

1. 签到、首次登录、发布笔记和点赞成功后，上报带业务幂等键的任务事件。
2. MySQL 通过 `uk_user_task_date` 原子累加当期任务进度，Redis 缓存热点进度。
3. 用户完成任务后携带稳定 `requestId` 领奖。积分同步入账；优惠券写入本地发券日志。
4. Lua 原子校验请求幂等、库存和一人一券，然后预扣 Redis 库存并写入 Stream。
5. Stream 消费者在独立事务中扣减 MySQL 库存、创建 `user_voucher`、更新发券日志和任务领奖状态。
6. 消费失败进入 Pending 重试；超过阈值转入死信并幂等归还 Redis 预留库存。

`task_event_record` 防止用户通过反复取消点赞再点赞刷进度。营销事件入口捕获自身异常，因此营销库或 Redis 异常不会让登录、签到和笔记主流程失败。

## 接口

所有接口均需要登录，使用现有 `authorization` 请求头：

- `GET /marketing/tasks`：查询任务、进度和领奖状态。
- `POST /marketing/tasks/{taskId}/reward`：领取奖励，请求体为 `{"requestId":"客户端UUID"}`。
- `GET /marketing/reward-grants/{requestId}`：轮询异步发券状态。
- `GET /marketing/vouchers`：查询我的优惠券。
- `GET /marketing/points`：查询积分余额。

客户端因超时重试领奖时必须复用原 `requestId`。券奖励接口返回 `PENDING` 代表已受理，不代表已经落库；轮询到 `SUCCESS` 后再刷新用户券列表。

## 初始化

新 Docker 数据卷会自动执行：

1. `src/main/resources/db/hmdp.sql`
2. `src/main/resources/db/migration/V2__marketing_task_voucher_loop.sql`

已有数据库只需手工执行第 2 个脚本。脚本使用 `CREATE TABLE IF NOT EXISTS`，基础任务和券模板使用幂等 upsert。

“完成一次消费”任务定义和奖励模板已经预置，但事件应在真实支付/核销成功事务提交后调用：

```java
taskEventService.recordSafely(userId, MarketingConstants.TASK_COMPLETE_ORDER, orderId.toString());
```

当前项目还没有支付核销接口，因此没有把“创建秒杀订单”错误地当成“完成消费”。
