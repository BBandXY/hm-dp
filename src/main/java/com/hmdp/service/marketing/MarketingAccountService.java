package com.hmdp.service.marketing;

import com.hmdp.dto.SeckillVoucherOrderDTO;
import com.hmdp.dto.UserVoucherDTO;
import com.hmdp.entity.UserPointsAccount;
import com.hmdp.mapper.UserPointsAccountMapper;
import com.hmdp.mapper.UserVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 用户营销资产查询。 */
@Service
public class MarketingAccountService {

    private static final String SECKILL_ORDER_TYPE = "SECKILL_ORDER";
    private static final String SECKILL_ORDER_SOURCE = "SECKILL_ORDER";

    @Resource
    private UserVoucherMapper userVoucherMapper;

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private UserPointsAccountMapper userPointsAccountMapper;

    public List<UserVoucherDTO> queryVouchers(Long userId) {
        List<UserVoucherDTO> result = new ArrayList<>();

        List<UserVoucherDTO> marketingVouchers = userVoucherMapper.selectUserVouchers(userId);
        if (marketingVouchers != null) {
            result.addAll(marketingVouchers);
        }

        List<SeckillVoucherOrderDTO> seckillOrders =
                voucherOrderMapper.selectUserSeckillVoucherOrders(userId);
        if (seckillOrders != null) {
            seckillOrders.stream()
                    .map(this::toUserVoucherView)
                    .forEach(result::add);
        }

        // 两个数据源分别按时间排序并不能保证合并后的顺序，因此在服务层统一排序。
        result.sort(Comparator.comparing(
                UserVoucherDTO::getReceiveTime,
                Comparator.nullsLast(Comparator.reverseOrder())
        ));
        return result;
    }

    private UserVoucherDTO toUserVoucherView(SeckillVoucherOrderDTO order) {
        return new UserVoucherDTO()
                .setId(order.getOrderId())
                .setVoucherId(order.getVoucherId())
                .setName(order.getName())
                .setVoucherType(SECKILL_ORDER_TYPE)
                .setShopId(order.getShopId())
                .setPayAmount(order.getPayAmount())
                .setActualAmount(order.getActualAmount())
                .setUseRule(order.getUseRule())
                .setSource(SECKILL_ORDER_SOURCE)
                .setStatus(toDisplayStatus(order.getOrderStatus()))
                .setReceiveTime(order.getCreateTime())
                .setExpireTime(order.getActivityEndTime())
                .setUseTime(order.getUseTime());
    }

    private String toDisplayStatus(Integer orderStatus) {
        if (orderStatus == null) {
            return "UNKNOWN";
        }
        switch (orderStatus) {
            case 1:
                return "PENDING_PAYMENT";
            case 2:
                return "UNUSED";
            case 3:
                return "USED";
            case 4:
                return "CANCELLED";
            case 5:
                return "REFUNDING";
            case 6:
                return "REFUNDED";
            default:
                return "UNKNOWN";
        }
    }

    public long queryPoints(Long userId) {
        UserPointsAccount account = userPointsAccountMapper.selectById(userId);
        return account == null || account.getBalance() == null ? 0L : account.getBalance();
    }
}
