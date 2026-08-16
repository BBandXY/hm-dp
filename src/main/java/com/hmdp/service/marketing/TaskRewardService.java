package com.hmdp.service.marketing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.constants.MarketingConstants;
import com.hmdp.dto.RewardClaimDTO;
import com.hmdp.entity.PointsChangeLog;
import com.hmdp.entity.TaskDefinition;
import com.hmdp.entity.UserTaskProgress;
import com.hmdp.entity.VoucherGrantLog;
import com.hmdp.entity.VoucherTemplate;
import com.hmdp.mapper.PointsChangeLogMapper;
import com.hmdp.mapper.TaskDefinitionMapper;
import com.hmdp.mapper.UserPointsAccountMapper;
import com.hmdp.mapper.UserTaskProgressMapper;
import com.hmdp.mapper.VoucherGrantLogMapper;
import com.hmdp.mapper.VoucherTemplateMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

/** 完成任务后的统一领奖入口。 */
@Slf4j
@Service
public class TaskRewardService {

    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    @Resource
    private TaskDefinitionMapper taskDefinitionMapper;

    @Resource
    private UserTaskProgressMapper userTaskProgressMapper;

    @Resource
    private UserPointsAccountMapper userPointsAccountMapper;

    @Resource
    private PointsChangeLogMapper pointsChangeLogMapper;

    @Resource
    private VoucherTemplateMapper voucherTemplateMapper;

    @Resource
    private VoucherGrantLogMapper voucherGrantLogMapper;

    @Resource
    private VoucherGrantPublisher voucherGrantPublisher;

    @Transactional
    public RewardClaimDTO claim(Long userId, Long taskId, String requestId) {
        validateRequestId(requestId);
        TaskDefinition task = taskDefinitionMapper.selectById(taskId);
        if (task == null || !Integer.valueOf(MarketingConstants.ENABLED).equals(task.getStatus())) {
            throw new IllegalArgumentException("任务不存在或已停用");
        }

        LocalDate taskDate = TaskProgressTransactionalService.taskDate(task.getTaskType());
        UserTaskProgress progress = userTaskProgressMapper.selectOne(
                new LambdaQueryWrapper<UserTaskProgress>()
                        .eq(UserTaskProgress::getUserId, userId)
                        .eq(UserTaskProgress::getTaskId, taskId)
                        .eq(UserTaskProgress::getTaskDate, taskDate)
                        .last("FOR UPDATE")
        );
        if (progress == null || !Boolean.TRUE.equals(progress.getCompleted())) {
            throw new IllegalStateException("任务尚未完成");
        }
        if (Boolean.TRUE.equals(progress.getRewardReceived())) {
            return buildResult(task, progress.getRewardRequestId(), MarketingConstants.GRANT_STATUS_SUCCESS,
                    "奖励已经领取");
        }
        if (progress.getRewardRequestId() != null) {
            return existingRequestResult(task, progress);
        }

        if (MarketingConstants.REWARD_TYPE_POINTS.equals(task.getRewardType())) {
            return grantPoints(userId, task, progress, requestId);
        }
        if (MarketingConstants.REWARD_TYPE_VOUCHER.equals(task.getRewardType())
                || MarketingConstants.REWARD_TYPE_SECKILL_QUALIFICATION.equals(task.getRewardType())) {
            return createVoucherGrant(userId, task, progress, requestId);
        }
        throw new IllegalStateException("不支持的奖励类型: " + task.getRewardType());
    }

    public RewardClaimDTO queryGrant(Long userId, String requestId) {
        VoucherGrantLog grant = voucherGrantLogMapper.selectOne(
                new LambdaQueryWrapper<VoucherGrantLog>()
                        .eq(VoucherGrantLog::getRequestId, requestId)
                        .eq(VoucherGrantLog::getUserId, userId)
                        .last("LIMIT 1")
        );
        if (grant == null) {
            throw new IllegalArgumentException("奖励请求不存在");
        }
        VoucherTemplate template = voucherTemplateMapper.selectById(grant.getVoucherId());
        String rewardType = template != null
                && MarketingConstants.REWARD_TYPE_SECKILL_QUALIFICATION.equals(template.getVoucherType())
                ? MarketingConstants.REWARD_TYPE_SECKILL_QUALIFICATION
                : MarketingConstants.REWARD_TYPE_VOUCHER;
        return new RewardClaimDTO()
                .setRequestId(requestId)
                .setRewardType(rewardType)
                .setVoucherId(grant.getVoucherId())
                .setStatus(grant.getStatus())
                .setMessage(grant.getFailReason());
    }

    private RewardClaimDTO grantPoints(Long userId,
                                       TaskDefinition task,
                                       UserTaskProgress progress,
                                       String requestId) {
        int points = task.getRewardValue() == null ? 0 : task.getRewardValue();
        if (points <= 0) {
            throw new IllegalStateException("积分奖励配置错误");
        }

        int inserted = pointsChangeLogMapper.insertIgnore(
                requestId, userId, progress.getId(), points, MarketingConstants.REWARD_SOURCE_TASK
        );
        if (inserted == 0) {
            PointsChangeLog existing = pointsChangeLogMapper.selectOne(
                    new LambdaQueryWrapper<PointsChangeLog>()
                            .eq(PointsChangeLog::getRequestId, requestId)
                            .last("LIMIT 1")
            );
            if (existing == null || !userId.equals(existing.getUserId())
                    || !progress.getId().equals(existing.getTaskProgressId())
                    || !Integer.valueOf(points).equals(existing.getPoints())) {
                throw new IllegalArgumentException("requestId 已被其他奖励使用");
            }
        } else {
            userPointsAccountMapper.addPoints(userId, points);
        }

        progress.setRewardReceived(true)
                .setRewardRequestId(requestId)
                .setUpdateTime(LocalDateTime.now());
        userTaskProgressMapper.updateById(progress);
        return buildResult(task, requestId, MarketingConstants.GRANT_STATUS_SUCCESS, "积分已到账");
    }

    private RewardClaimDTO createVoucherGrant(Long userId,
                                              TaskDefinition task,
                                              UserTaskProgress progress,
                                              String requestId) {
        VoucherTemplate template = validateVoucher(task.getRewardId());
        int inserted = voucherGrantLogMapper.insertRequestIgnore(
                requestId,
                userId,
                template.getId(),
                progress.getId(),
                MarketingConstants.REWARD_SOURCE_TASK
        );
        if (inserted == 0) {
            VoucherGrantLog existing = findGrant(requestId);
            if (existing == null || !userId.equals(existing.getUserId())
                    || !progress.getId().equals(existing.getTaskProgressId())) {
                throw new IllegalArgumentException("requestId 已被其他奖励使用");
            }
        }

        progress.setRewardRequestId(requestId).setUpdateTime(LocalDateTime.now());
        userTaskProgressMapper.updateById(progress);
        publishAfterCommit(requestId);
        return buildResult(task, requestId, MarketingConstants.GRANT_STATUS_PENDING,
                "奖励已受理，正在异步发券");
    }

    private RewardClaimDTO existingRequestResult(TaskDefinition task, UserTaskProgress progress) {
        VoucherGrantLog grant = findGrant(progress.getRewardRequestId());
        String status = grant == null ? MarketingConstants.GRANT_STATUS_PENDING : grant.getStatus();
        String message = grant == null ? "奖励正在处理" : grant.getFailReason();
        return buildResult(task, progress.getRewardRequestId(), status, message);
    }

    private VoucherTemplate validateVoucher(Long voucherId) {
        if (voucherId == null) {
            throw new IllegalStateException("券奖励未配置 voucherId");
        }
        VoucherTemplate template = voucherTemplateMapper.selectById(voucherId);
        LocalDateTime now = LocalDateTime.now();
        if (template == null || !Integer.valueOf(MarketingConstants.ENABLED).equals(template.getStatus())) {
            throw new IllegalStateException("奖励券不存在或已停用");
        }
        if (template.getBeginTime() != null && now.isBefore(template.getBeginTime())) {
            throw new IllegalStateException("奖励券领取尚未开始");
        }
        if (template.getEndTime() != null && now.isAfter(template.getEndTime())) {
            throw new IllegalStateException("奖励券领取已经结束");
        }
        return template;
    }

    private void publishAfterCommit(String requestId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    voucherGrantPublisher.publish(requestId);
                } catch (Exception e) {
                    // voucher_grant_log 的 CREATED 记录会被可靠发布定时任务再次扫描。
                    log.warn("事务提交后发布发券请求失败，等待定时补偿。requestId={}", requestId, e);
                }
            }
        });
    }

    private VoucherGrantLog findGrant(String requestId) {
        return voucherGrantLogMapper.selectOne(
                new LambdaQueryWrapper<VoucherGrantLog>()
                        .eq(VoucherGrantLog::getRequestId, requestId)
                        .last("LIMIT 1")
        );
    }

    private RewardClaimDTO buildResult(TaskDefinition task,
                                       String requestId,
                                       String status,
                                       String message) {
        return new RewardClaimDTO()
                .setRequestId(requestId)
                .setRewardType(task.getRewardType())
                .setStatus(status)
                .setPoints(MarketingConstants.REWARD_TYPE_POINTS.equals(task.getRewardType())
                        ? task.getRewardValue() : null)
                .setVoucherId(MarketingConstants.REWARD_TYPE_POINTS.equals(task.getRewardType())
                        ? null : task.getRewardId())
                .setMessage(message);
    }

    private void validateRequestId(String requestId) {
        if (requestId == null || !REQUEST_ID_PATTERN.matcher(requestId).matches()) {
            throw new IllegalArgumentException("requestId 必须是 8~64 位字母、数字、下划线或短横线");
        }
    }
}
