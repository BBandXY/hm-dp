package com.hmdp.constants;

import java.time.LocalDate;

/**
 * 营销模块使用的稳定业务常量。
 *
 * <p>状态值集中维护，避免控制器、任务服务和 Stream 消费者各自使用魔法字符串。</p>
 */
public final class MarketingConstants {

    private MarketingConstants() {
    }

    public static final int ENABLED = 1;

    public static final String TASK_TYPE_DAILY = "DAILY";
    public static final String TASK_TYPE_ONCE = "ONCE";
    public static final LocalDate ONCE_TASK_DATE = LocalDate.of(1970, 1, 1);

    public static final String REWARD_TYPE_POINTS = "POINTS";
    public static final String REWARD_TYPE_VOUCHER = "VOUCHER";
    public static final String REWARD_TYPE_SECKILL_QUALIFICATION = "SECKILL_QUALIFICATION";

    public static final String TASK_DAILY_SIGN = "DAILY_SIGN";
    public static final String TASK_CONTINUOUS_SIGN_7 = "CONTINUOUS_SIGN_7";
    public static final String TASK_PUBLISH_BLOG = "PUBLISH_BLOG";
    public static final String TASK_LIKE_BLOG = "LIKE_BLOG";
    public static final String TASK_COMPLETE_ORDER = "COMPLETE_ORDER";
    public static final String TASK_NEW_USER_LOGIN = "NEW_USER_LOGIN";

    public static final String GRANT_STATUS_CREATED = "CREATED";
    public static final String GRANT_STATUS_PENDING = "PENDING";
    public static final String GRANT_STATUS_SUCCESS = "SUCCESS";
    public static final String GRANT_STATUS_FAILED = "FAILED";

    public static final String USER_VOUCHER_UNUSED = "UNUSED";
    public static final String USER_VOUCHER_USED = "USED";
    public static final String USER_VOUCHER_EXPIRED = "EXPIRED";

    public static final String REWARD_SOURCE_TASK = "TASK";
}
