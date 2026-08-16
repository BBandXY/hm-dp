-- 每日任务—优惠券营销闭环。金额字段统一使用“分”。

CREATE TABLE IF NOT EXISTS `voucher_template` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '券模板主键',
  `merchant_id` bigint(20) UNSIGNED DEFAULT NULL COMMENT '商户 id，NULL 表示平台券',
  `name` varchar(128) NOT NULL COMMENT '券名称',
  `voucher_type` varchar(32) NOT NULL COMMENT 'FULL_REDUCTION/DISCOUNT/SECKILL_QUALIFICATION',
  `threshold_amount` bigint(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT '使用门槛，单位分',
  `discount_amount` bigint(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT '优惠金额，单位分',
  `total_stock` int(11) UNSIGNED NOT NULL COMMENT '发行总量',
  `remaining_stock` int(11) UNSIGNED NOT NULL COMMENT '数据库剩余库存',
  `begin_time` datetime NOT NULL COMMENT '可领取时间',
  `end_time` datetime NOT NULL COMMENT '领取截止时间',
  `valid_days` int(11) UNSIGNED NOT NULL DEFAULT 30 COMMENT '领取后有效天数',
  `receive_rule` varchar(1024) DEFAULT NULL COMMENT '领取规则说明或 JSON',
  `use_rule` varchar(1024) DEFAULT NULL COMMENT '使用规则说明或 JSON',
  `status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '1启用 2停用 3过期',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_merchant_status` (`merchant_id`, `status`),
  KEY `idx_end_time` (`end_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='营销优惠券模板';

CREATE TABLE IF NOT EXISTS `task_definition` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_code` varchar(64) NOT NULL COMMENT '稳定业务编码',
  `task_name` varchar(128) NOT NULL,
  `task_type` varchar(16) NOT NULL COMMENT 'DAILY/ONCE',
  `target_value` int(11) UNSIGNED NOT NULL DEFAULT 1,
  `reward_type` varchar(32) NOT NULL COMMENT 'POINTS/VOUCHER/SECKILL_QUALIFICATION',
  `reward_id` bigint(20) UNSIGNED DEFAULT NULL COMMENT '券模板 id',
  `reward_value` int(11) UNSIGNED NOT NULL DEFAULT 1 COMMENT '积分数量或券数量',
  `status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_code` (`task_code`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务定义';

CREATE TABLE IF NOT EXISTS `user_task_progress` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `task_id` bigint(20) UNSIGNED NOT NULL,
  `progress` int(11) UNSIGNED NOT NULL DEFAULT 0,
  `task_date` date NOT NULL COMMENT 'DAILY 为当天，ONCE 固定为 1970-01-01',
  `completed` tinyint(1) UNSIGNED NOT NULL DEFAULT 0,
  `reward_received` tinyint(1) UNSIGNED NOT NULL DEFAULT 0,
  `reward_request_id` varchar(64) DEFAULT NULL COMMENT '异步奖励请求 id',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_task_date` (`user_id`, `task_id`, `task_date`),
  KEY `idx_reward_request` (`reward_request_id`),
  KEY `idx_task_date` (`task_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户任务进度';

CREATE TABLE IF NOT EXISTS `task_event_record` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `task_code` varchar(64) NOT NULL,
  `biz_id` varchar(128) NOT NULL COMMENT '笔记 id、订单 id 或签到日期',
  `task_date` date NOT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_event` (`user_id`, `task_code`, `biz_id`, `task_date`),
  KEY `idx_task_date` (`task_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务事件幂等记录';

CREATE TABLE IF NOT EXISTS `user_voucher` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `voucher_id` bigint(20) UNSIGNED NOT NULL,
  `grant_request_id` varchar(64) NOT NULL,
  `source` varchar(32) NOT NULL,
  `status` varchar(16) NOT NULL COMMENT 'UNUSED/USED/EXPIRED',
  `receive_time` datetime NOT NULL,
  `expire_time` datetime NOT NULL,
  `use_time` datetime DEFAULT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_voucher` (`user_id`, `voucher_id`),
  UNIQUE KEY `uk_grant_request` (`grant_request_id`),
  KEY `idx_user_status_expire` (`user_id`, `status`, `expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户营销券';

CREATE TABLE IF NOT EXISTS `voucher_grant_log` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `request_id` varchar(64) NOT NULL,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `voucher_id` bigint(20) UNSIGNED NOT NULL,
  `task_progress_id` bigint(20) UNSIGNED DEFAULT NULL,
  `source` varchar(32) NOT NULL,
  `status` varchar(16) NOT NULL COMMENT 'CREATED/PENDING/SUCCESS/FAILED',
  `fail_reason` varchar(500) DEFAULT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_grant_request` (`request_id`),
  KEY `idx_status_update` (`status`, `update_time`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发券日志与本地可靠消息';

CREATE TABLE IF NOT EXISTS `user_points_account` (
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `balance` bigint(20) UNSIGNED NOT NULL DEFAULT 0,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户积分账户';

CREATE TABLE IF NOT EXISTS `points_change_log` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `request_id` varchar(64) NOT NULL,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `task_progress_id` bigint(20) UNSIGNED NOT NULL,
  `points` int(11) UNSIGNED NOT NULL,
  `source` varchar(32) NOT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_points_request` (`request_id`),
  KEY `idx_user_time` (`user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分变动流水';

INSERT INTO `voucher_template`
(`id`, `merchant_id`, `name`, `voucher_type`, `threshold_amount`, `discount_amount`,
 `total_stock`, `remaining_stock`, `begin_time`, `end_time`, `valid_days`, `receive_rule`, `use_rule`, `status`)
VALUES
(10001, NULL, '连续签到7天5元券', 'FULL_REDUCTION', 3000, 500,
 10000, 10000, '2020-01-01 00:00:00', '2035-12-31 23:59:59', 30, '连续签到7天可领取', '满30元可用', 1),
(10002, NULL, '新用户8元券', 'FULL_REDUCTION', 4000, 800,
 10000, 10000, '2020-01-01 00:00:00', '2035-12-31 23:59:59', 30, '新用户首次登录可领取', '满40元可用', 1),
(10003, NULL, '消费任务秒杀资格券', 'SECKILL_QUALIFICATION', 0, 0,
 10000, 10000, '2020-01-01 00:00:00', '2035-12-31 23:59:59', 7, '完成一次消费可领取', '用于指定秒杀活动资格校验', 1)
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `use_rule` = VALUES(`use_rule`);

INSERT INTO `task_definition`
(`id`, `task_code`, `task_name`, `task_type`, `target_value`, `reward_type`, `reward_id`, `reward_value`, `status`)
VALUES
(101, 'DAILY_SIGN', '每日签到', 'DAILY', 1, 'POINTS', NULL, 10, 1),
(102, 'CONTINUOUS_SIGN_7', '连续签到7天', 'ONCE', 7, 'VOUCHER', 10001, 1, 1),
(103, 'PUBLISH_BLOG', '发布一篇探店笔记', 'DAILY', 1, 'POINTS', NULL, 20, 1),
(104, 'LIKE_BLOG', '点赞3篇探店笔记', 'DAILY', 3, 'POINTS', NULL, 10, 1),
(105, 'COMPLETE_ORDER', '完成一次消费', 'DAILY', 1, 'SECKILL_QUALIFICATION', 10003, 1, 1),
(106, 'NEW_USER_LOGIN', '新用户首次登录', 'ONCE', 1, 'VOUCHER', 10002, 1, 1)
ON DUPLICATE KEY UPDATE
  `task_name` = VALUES(`task_name`),
  `task_type` = VALUES(`task_type`),
  `target_value` = VALUES(`target_value`),
  `reward_type` = VALUES(`reward_type`),
  `reward_id` = VALUES(`reward_id`),
  `reward_value` = VALUES(`reward_value`),
  `status` = VALUES(`status`);
