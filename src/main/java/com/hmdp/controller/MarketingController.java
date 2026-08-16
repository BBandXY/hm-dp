package com.hmdp.controller;

import com.hmdp.dto.ClaimTaskRewardDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.marketing.MarketingAccountService;
import com.hmdp.service.marketing.MarketingRateLimiter;
import com.hmdp.service.marketing.TaskQueryService;
import com.hmdp.service.marketing.TaskRewardService;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.Collections;

/** 每日任务、领奖和用户营销资产接口。 */
@RestController
@RequestMapping("/marketing")
public class MarketingController {

    @Resource
    private TaskQueryService taskQueryService;

    @Resource
    private TaskRewardService taskRewardService;

    @Resource
    private MarketingAccountService marketingAccountService;

    @Resource
    private MarketingRateLimiter rateLimiter;

    @GetMapping("/tasks")
    public Result queryTasks() {
        return Result.ok(taskQueryService.queryTasks(currentUserId()));
    }

    @PostMapping("/tasks/{taskId}/reward")
    public Result claimReward(@PathVariable("taskId") Long taskId,
                              @RequestBody ClaimTaskRewardDTO request) {
        Long userId = currentUserId();
        if (!rateLimiter.allowRewardClaim(userId)) {
            return Result.fail("请求过于频繁，请稍后再试");
        }
        String requestId = request == null ? null : request.getRequestId();
        return Result.ok(taskRewardService.claim(userId, taskId, requestId));
    }

    @GetMapping("/reward-grants/{requestId}")
    public Result queryRewardGrant(@PathVariable("requestId") String requestId) {
        return Result.ok(taskRewardService.queryGrant(currentUserId(), requestId));
    }

    @GetMapping("/vouchers")
    public Result queryMyVouchers() {
        return Result.ok(marketingAccountService.queryVouchers(currentUserId()));
    }

    @GetMapping("/points")
    public Result queryMyPoints() {
        return Result.ok(Collections.singletonMap("balance",
                marketingAccountService.queryPoints(currentUserId())));
    }

    private Long currentUserId() {
        return UserHolder.getUser().getId();
    }
}
