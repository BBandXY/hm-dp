package com.hmdp.service.marketing;

import com.hmdp.dto.SeckillVoucherOrderDTO;
import com.hmdp.dto.UserVoucherDTO;
import com.hmdp.mapper.UserPointsAccountMapper;
import com.hmdp.mapper.UserVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketingAccountServiceTest {

    @Mock
    private UserVoucherMapper userVoucherMapper;

    @Mock
    private VoucherOrderMapper voucherOrderMapper;

    @Mock
    private UserPointsAccountMapper userPointsAccountMapper;

    @InjectMocks
    private MarketingAccountService service;

    @Test
    void shouldMergeMarketingVouchersAndSeckillOrdersByReceiveTime() {
        Long userId = 10L;
        UserVoucherDTO marketingVoucher = new UserVoucherDTO()
                .setId(1L)
                .setSource("TASK_REWARD")
                .setStatus("UNUSED")
                .setReceiveTime(LocalDateTime.of(2026, 8, 18, 10, 0));
        SeckillVoucherOrderDTO seckillOrder = new SeckillVoucherOrderDTO()
                .setOrderId(2001L)
                .setVoucherId(20L)
                .setShopId(2L)
                .setName("限时秒杀券")
                .setPayAmount(4750L)
                .setActualAmount(5000L)
                .setOrderStatus(1)
                .setCreateTime(LocalDateTime.of(2026, 8, 19, 10, 0));
        when(userVoucherMapper.selectUserVouchers(userId))
                .thenReturn(Collections.singletonList(marketingVoucher));
        when(voucherOrderMapper.selectUserSeckillVoucherOrders(userId))
                .thenReturn(Collections.singletonList(seckillOrder));

        List<UserVoucherDTO> result = service.queryVouchers(userId);

        assertEquals(2, result.size());
        assertEquals(2001L, result.get(0).getId());
        assertEquals("SECKILL_ORDER", result.get(0).getVoucherType());
        assertEquals("PENDING_PAYMENT", result.get(0).getStatus());
        assertEquals(4750L, result.get(0).getPayAmount());
        assertEquals(5000L, result.get(0).getActualAmount());
        assertEquals(1L, result.get(1).getId());
    }

    @Test
    void shouldTranslateEverySeckillOrderStatus() {
        Long userId = 10L;
        List<SeckillVoucherOrderDTO> orders = Arrays.asList(
                order(1L, 1), order(2L, 2), order(3L, 3),
                order(4L, 4), order(5L, 5), order(6L, 6), order(7L, 99)
        );
        when(userVoucherMapper.selectUserVouchers(userId)).thenReturn(Collections.emptyList());
        when(voucherOrderMapper.selectUserSeckillVoucherOrders(userId)).thenReturn(orders);

        List<UserVoucherDTO> result = service.queryVouchers(userId);

        assertEquals(Arrays.asList(
                        "PENDING_PAYMENT", "UNUSED", "USED", "CANCELLED",
                        "REFUNDING", "REFUNDED", "UNKNOWN"),
                Arrays.asList(
                        result.get(0).getStatus(), result.get(1).getStatus(), result.get(2).getStatus(),
                        result.get(3).getStatus(), result.get(4).getStatus(), result.get(5).getStatus(),
                        result.get(6).getStatus()
                ));
    }

    private SeckillVoucherOrderDTO order(Long orderId, Integer status) {
        return new SeckillVoucherOrderDTO()
                .setOrderId(orderId)
                .setOrderStatus(status)
                .setCreateTime(LocalDateTime.of(2026, 8, 19, 10, 0).minusMinutes(orderId));
    }
}
