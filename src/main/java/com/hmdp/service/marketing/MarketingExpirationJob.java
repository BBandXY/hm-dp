package com.hmdp.service.marketing;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.constants.MarketingConstants;
import com.hmdp.entity.UserVoucher;
import com.hmdp.entity.VoucherTemplate;
import com.hmdp.mapper.UserVoucherMapper;
import com.hmdp.mapper.VoucherTemplateMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/** 优惠券过期维护；每日任务通过 task_date 分区，无需批量清零。 */
@Component
public class MarketingExpirationJob {

    @Resource
    private UserVoucherMapper userVoucherMapper;

    @Resource
    private VoucherTemplateMapper voucherTemplateMapper;

    @Scheduled(cron = "${hmdp.marketing.expire-cron:0 5 * * * ?}")
    public void expireVouchers() {
        LocalDateTime now = LocalDateTime.now();
        userVoucherMapper.update(
                null,
                new LambdaUpdateWrapper<UserVoucher>()
                        .set(UserVoucher::getStatus, MarketingConstants.USER_VOUCHER_EXPIRED)
                        .eq(UserVoucher::getStatus, MarketingConstants.USER_VOUCHER_UNUSED)
                        .lt(UserVoucher::getExpireTime, now)
        );
        voucherTemplateMapper.update(
                null,
                new LambdaUpdateWrapper<VoucherTemplate>()
                        .set(VoucherTemplate::getStatus, 3)
                        .eq(VoucherTemplate::getStatus, MarketingConstants.ENABLED)
                        .lt(VoucherTemplate::getEndTime, now)
        );
    }
}
