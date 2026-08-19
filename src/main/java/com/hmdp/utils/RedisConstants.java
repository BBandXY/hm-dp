package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 3600L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_BEGIN_KEY = "seckill:begin:";
    public static final String SECKILL_END_KEY = "seckill:end:";
    public static final String SECKILL_ORDER_PENDING_KEY = "seckill:orders:pending";
    public static final String ORDER_STATUS_KEY = "seckill:order:status:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    /** 点赞计数尚未落库的净增量，field 为 blogId。 */
    public static final String BLOG_LIKED_DELTA_KEY = "blog:liked:delta";
    /** 有待落库增量的 blogId 队列，score 为首次进入本批次的时间。 */
    public static final String BLOG_LIKED_DIRTY_KEY = "blog:liked:dirty";
    /** 当前正在落库的增量快照。新点赞继续写入 BLOG_LIKED_DELTA_KEY。 */
    public static final String BLOG_LIKED_PROCESSING_KEY = "blog:liked:delta:processing";
    public static final String BLOG_LIKED_PROCESSING_BATCH_KEY = "blog:liked:delta:processing:batch";
    /** Redis 中的实时逻辑点赞数，用于低频对账。 */
    public static final String BLOG_LIKED_COUNT_KEY = "blog:liked:count";
    /** 上线前已有、未保存在点赞关系 ZSet 中的点赞数。 */
    public static final String BLOG_LIKED_BASELINE_KEY = "blog:liked:baseline";
    /** 需要周期性核对点赞数的 blogId 集合，score 为上次对账时间。 */
    public static final String BLOG_LIKED_RECONCILE_KEY = "blog:liked:reconcile";
    public static final String BLOG_LIKED_FLUSH_LOCK = "lock:blog:liked:flush";
    public static final String BLOG_LIKED_RECONCILE_LOCK = "lock:blog:liked:reconcile";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
