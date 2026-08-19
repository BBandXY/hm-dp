-- 点赞增量异步落库的数据库幂等屏障。
-- 批次记录与 tb_blog.liked 更新在同一事务提交，Redis 重复投递不会重复累加。

CREATE TABLE IF NOT EXISTS `tb_blog_like_sync_batch` (
  `batch_id` varchar(64) NOT NULL COMMENT 'Redis processing 快照的唯一批次号',
  `item_count` int(11) UNSIGNED NOT NULL COMMENT '批次内 blog 数量，便于审计',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`batch_id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点赞计数异步落库幂等批次';
