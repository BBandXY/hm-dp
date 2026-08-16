package com.hmdp.service.marketing;

import com.hmdp.constants.MarketingConstants;
import com.hmdp.dto.VoucherGrantMessage;
import com.hmdp.entity.UserVoucher;
import com.hmdp.entity.VoucherGrantLog;
import com.hmdp.mapper.UserTaskProgressMapper;
import com.hmdp.mapper.UserVoucherMapper;
import com.hmdp.mapper.VoucherGrantLogMapper;
import com.hmdp.mapper.VoucherTemplateMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoucherGrantTransactionalServiceTest {

    @Mock
    private VoucherGrantLogMapper voucherGrantLogMapper;

    @Mock
    private UserVoucherMapper userVoucherMapper;

    @Mock
    private VoucherTemplateMapper voucherTemplateMapper;

    @Mock
    private UserTaskProgressMapper userTaskProgressMapper;

    @InjectMocks
    private VoucherGrantTransactionalService service;

    @Test
    void shouldDecrementDatabaseStockAndCreateUserVoucher() {
        VoucherGrantMessage message = message();
        VoucherGrantLog grant = grant();
        when(voucherGrantLogMapper.selectByRequestIdForUpdate(message.getRequestId())).thenReturn(grant);
        when(userVoucherMapper.selectOne(any())).thenReturn(null);
        when(voucherTemplateMapper.decrementStock(message.getVoucherId())).thenReturn(1);

        VoucherGrantTransactionalService.PersistStatus status = service.persistGrant(message);

        assertEquals(VoucherGrantTransactionalService.PersistStatus.CREATED, status);
        ArgumentCaptor<UserVoucher> voucherCaptor = ArgumentCaptor.forClass(UserVoucher.class);
        verify(userVoucherMapper).insert(voucherCaptor.capture());
        assertEquals(message.getUserId(), voucherCaptor.getValue().getUserId());
        assertEquals(MarketingConstants.USER_VOUCHER_UNUSED, voucherCaptor.getValue().getStatus());
        assertNotNull(voucherCaptor.getValue().getExpireTime());
        verify(voucherTemplateMapper).decrementStock(message.getVoucherId());
    }

    @Test
    void shouldTreatExistingUserVoucherAsDuplicateWithoutDecrementingStock() {
        VoucherGrantMessage message = message();
        when(voucherGrantLogMapper.selectByRequestIdForUpdate(message.getRequestId())).thenReturn(grant());
        when(userVoucherMapper.selectOne(any())).thenReturn(
                new UserVoucher().setId(1L).setUserId(message.getUserId()).setVoucherId(message.getVoucherId())
        );

        VoucherGrantTransactionalService.PersistStatus status = service.persistGrant(message);

        assertEquals(VoucherGrantTransactionalService.PersistStatus.DUPLICATE, status);
        verify(voucherTemplateMapper, never()).decrementStock(any());
        verify(userVoucherMapper, never()).insert(any());
    }

    private VoucherGrantMessage message() {
        return new VoucherGrantMessage()
                .setRequestId("request-1001")
                .setUserId(10L)
                .setVoucherId(10001L)
                .setSource(MarketingConstants.REWARD_SOURCE_TASK)
                .setExpireAt(System.currentTimeMillis() + 86_400_000L);
    }

    private VoucherGrantLog grant() {
        return new VoucherGrantLog()
                .setId(30L)
                .setRequestId("request-1001")
                .setUserId(10L)
                .setVoucherId(10001L)
                .setStatus(MarketingConstants.GRANT_STATUS_PENDING);
    }
}
