package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.VoucherTemplate;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface VoucherTemplateMapper extends BaseMapper<VoucherTemplate> {

    @Update("UPDATE voucher_template SET remaining_stock = remaining_stock - 1, " +
            "update_time = CURRENT_TIMESTAMP WHERE id = #{voucherId} AND status = 1 AND remaining_stock > 0")
    int decrementStock(@Param("voucherId") Long voucherId);
}
