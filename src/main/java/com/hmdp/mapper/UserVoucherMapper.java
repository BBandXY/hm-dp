package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.dto.UserVoucherDTO;
import com.hmdp.entity.UserVoucher;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface UserVoucherMapper extends BaseMapper<UserVoucher> {

    @Select("SELECT uv.id, uv.voucher_id, vt.name, vt.voucher_type, vt.merchant_id, " +
            "vt.threshold_amount, vt.discount_amount, vt.use_rule, uv.source, uv.status, " +
            "uv.receive_time, uv.expire_time, uv.use_time " +
            "FROM user_voucher uv JOIN voucher_template vt ON vt.id = uv.voucher_id " +
            "WHERE uv.user_id = #{userId} ORDER BY uv.receive_time DESC")
    List<UserVoucherDTO> selectUserVouchers(@Param("userId") Long userId);

    @Select("SELECT user_id FROM user_voucher WHERE voucher_id = #{voucherId} " +
            "AND status IN ('UNUSED', 'USED')")
    List<Long> selectOwnerIds(@Param("voucherId") Long voucherId);
}
