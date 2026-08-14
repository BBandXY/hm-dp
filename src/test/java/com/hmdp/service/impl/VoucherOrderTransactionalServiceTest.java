package com.hmdp.service.impl;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoucherOrderTransactionalServiceTest {

    @Mock
    private VoucherOrderMapper voucherOrderMapper;

    @Mock
    private SeckillVoucherMapper seckillVoucherMapper;

    @InjectMocks
    private VoucherOrderTransactionalService service;

    @Test
    void shouldTreatExistingOrderIdAsIdempotent() {
        VoucherOrder order = order();
        when(voucherOrderMapper.selectById(order.getId())).thenReturn(order);

        VoucherOrderTransactionalService.PersistResult result = service.createVoucherOrder(order);

        assertEquals(VoucherOrderTransactionalService.PersistStatus.IDEMPOTENT, result.getStatus());
        verify(seckillVoucherMapper, never()).update(isNull(), any());
        verify(voucherOrderMapper, never()).insert(any());
    }

    @Test
    void shouldDecrementStockAndInsertOrder() {
        VoucherOrder order = order();
        when(voucherOrderMapper.selectById(order.getId())).thenReturn(null);
        when(voucherOrderMapper.selectOne(any())).thenReturn(null);
        when(seckillVoucherMapper.update(isNull(), any())).thenReturn(1);

        VoucherOrderTransactionalService.PersistResult result = service.createVoucherOrder(order);

        assertEquals(VoucherOrderTransactionalService.PersistStatus.CREATED, result.getStatus());
        verify(voucherOrderMapper).insert(order);
    }

    @Test
    void shouldFailWhenDatabaseStockIsEmpty() {
        VoucherOrder order = order();
        when(voucherOrderMapper.selectById(order.getId())).thenReturn(null);
        when(voucherOrderMapper.selectOne(any())).thenReturn(null);
        when(seckillVoucherMapper.update(isNull(), any())).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.createVoucherOrder(order));
        verify(voucherOrderMapper, never()).insert(any());
    }

    private VoucherOrder order() {
        return new VoucherOrder().setId(1001L).setUserId(10L).setVoucherId(20L);
    }
}
