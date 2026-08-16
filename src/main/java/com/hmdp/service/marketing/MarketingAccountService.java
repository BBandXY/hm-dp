package com.hmdp.service.marketing;

import com.hmdp.dto.UserVoucherDTO;
import com.hmdp.entity.UserPointsAccount;
import com.hmdp.mapper.UserPointsAccountMapper;
import com.hmdp.mapper.UserVoucherMapper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/** 用户营销资产查询。 */
@Service
public class MarketingAccountService {

    @Resource
    private UserVoucherMapper userVoucherMapper;

    @Resource
    private UserPointsAccountMapper userPointsAccountMapper;

    public List<UserVoucherDTO> queryVouchers(Long userId) {
        return userVoucherMapper.selectUserVouchers(userId);
    }

    public long queryPoints(Long userId) {
        UserPointsAccount account = userPointsAccountMapper.selectById(userId);
        return account == null || account.getBalance() == null ? 0L : account.getBalance();
    }
}
