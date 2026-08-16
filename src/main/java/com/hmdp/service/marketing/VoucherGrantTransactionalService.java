package com.hmdp.service.marketing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.constants.MarketingConstants;
import com.hmdp.dto.VoucherGrantMessage;
import com.hmdp.entity.UserTaskProgress;
import com.hmdp.entity.UserVoucher;
import com.hmdp.entity.VoucherGrantLog;
import com.hmdp.mapper.UserTaskProgressMapper;
import com.hmdp.mapper.UserVoucherMapper;
import com.hmdp.mapper.VoucherGrantLogMapper;
import com.hmdp.mapper.VoucherTemplateMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/** Redis Stream 发券消息的数据库事务边界。 */
@Service
public class VoucherGrantTransactionalService {

    @Resource
    private VoucherGrantLogMapper voucherGrantLogMapper;

    @Resource
    private UserVoucherMapper userVoucherMapper;

    @Resource
    private VoucherTemplateMapper voucherTemplateMapper;

    @Resource
    private UserTaskProgressMapper userTaskProgressMapper;

    @Transactional
    public PersistStatus persistGrant(VoucherGrantMessage message) {
        VoucherGrantLog grant = voucherGrantLogMapper.selectByRequestIdForUpdate(message.getRequestId());
        validateMessage(grant, message);

        if (MarketingConstants.GRANT_STATUS_SUCCESS.equals(grant.getStatus())) {
            markTaskRewardReceived(grant);
            return PersistStatus.IDEMPOTENT;
        }
        if (MarketingConstants.GRANT_STATUS_FAILED.equals(grant.getStatus())) {
            return PersistStatus.FAILED;
        }

        UserVoucher existing = findUserVoucher(message.getUserId(), message.getVoucherId());
        if (existing != null) {
            markGrantSuccess(grant);
            return PersistStatus.DUPLICATE;
        }

        int stockUpdated = voucherTemplateMapper.decrementStock(message.getVoucherId());
        if (stockUpdated == 0) {
            throw new IllegalStateException("数据库优惠券库存不足或模板已停用");
        }

        LocalDateTime now = LocalDateTime.now();
        UserVoucher userVoucher = new UserVoucher()
                .setUserId(message.getUserId())
                .setVoucherId(message.getVoucherId())
                .setGrantRequestId(message.getRequestId())
                .setSource(message.getSource())
                .setStatus(MarketingConstants.USER_VOUCHER_UNUSED)
                .setReceiveTime(now)
                .setExpireTime(message.expireTime());
        userVoucherMapper.insert(userVoucher);
        markGrantSuccess(grant);
        return PersistStatus.CREATED;
    }

    @Transactional
    public boolean completeAlreadyOwned(String requestId) {
        VoucherGrantLog grant = voucherGrantLogMapper.selectByRequestIdForUpdate(requestId);
        if (grant == null) {
            return false;
        }
        UserVoucher existing = findUserVoucher(grant.getUserId(), grant.getVoucherId());
        if (existing == null) {
            return false;
        }
        markGrantSuccess(grant);
        return true;
    }

    @Transactional
    public void markFailed(String requestId, String reason) {
        VoucherGrantLog grant = voucherGrantLogMapper.selectByRequestIdForUpdate(requestId);
        if (grant == null || MarketingConstants.GRANT_STATUS_SUCCESS.equals(grant.getStatus())) {
            return;
        }
        grant.setStatus(MarketingConstants.GRANT_STATUS_FAILED)
                .setFailReason(trimReason(reason))
                .setUpdateTime(LocalDateTime.now());
        voucherGrantLogMapper.updateById(grant);

        if (grant.getTaskProgressId() != null) {
            userTaskProgressMapper.update(
                    null,
                    new LambdaUpdateWrapper<UserTaskProgress>()
                            .set(UserTaskProgress::getRewardRequestId, null)
                            .eq(UserTaskProgress::getId, grant.getTaskProgressId())
                            .eq(UserTaskProgress::getRewardRequestId, requestId)
                            .eq(UserTaskProgress::getRewardReceived, false)
            );
        }
    }

    private UserVoucher findUserVoucher(Long userId, Long voucherId) {
        return userVoucherMapper.selectOne(
                new LambdaQueryWrapper<UserVoucher>()
                        .eq(UserVoucher::getUserId, userId)
                        .eq(UserVoucher::getVoucherId, voucherId)
                        .last("LIMIT 1")
        );
    }

    private void markGrantSuccess(VoucherGrantLog grant) {
        grant.setStatus(MarketingConstants.GRANT_STATUS_SUCCESS)
                .setFailReason(null)
                .setUpdateTime(LocalDateTime.now());
        voucherGrantLogMapper.updateById(grant);
        markTaskRewardReceived(grant);
    }

    private void markTaskRewardReceived(VoucherGrantLog grant) {
        if (grant.getTaskProgressId() == null) {
            return;
        }
        userTaskProgressMapper.update(
                null,
                new LambdaUpdateWrapper<UserTaskProgress>()
                        .set(UserTaskProgress::getRewardReceived, true)
                        .eq(UserTaskProgress::getId, grant.getTaskProgressId())
                        .eq(UserTaskProgress::getRewardRequestId, grant.getRequestId())
        );
    }

    private void validateMessage(VoucherGrantLog grant, VoucherGrantMessage message) {
        if (grant == null) {
            throw new IllegalArgumentException("发券请求不存在: " + message.getRequestId());
        }
        if (!grant.getUserId().equals(message.getUserId())
                || !grant.getVoucherId().equals(message.getVoucherId())) {
            throw new IllegalArgumentException("发券消息和本地请求不一致: " + message.getRequestId());
        }
    }

    private String trimReason(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 500 ? reason : reason.substring(0, 500);
    }

    public enum PersistStatus {
        CREATED,
        IDEMPOTENT,
        DUPLICATE,
        FAILED
    }
}
