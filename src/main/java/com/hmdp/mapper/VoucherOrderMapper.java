package com.hmdp.mapper;

import com.hmdp.dto.SeckillVoucherOrderDTO;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface VoucherOrderMapper extends BaseMapper<VoucherOrder> {

    /**
     * 查询用户抢购的秒杀券订单，并一次性补齐券和活动信息，避免列表查询产生 N+1。
     */
    @Select("SELECT vo.id AS orderId, vo.voucher_id AS voucherId, " +
            "v.shop_id AS shopId, v.title AS name, v.rules AS useRule, " +
            "v.pay_value AS payAmount, v.actual_value AS actualAmount, " +
            "vo.status AS orderStatus, vo.create_time AS createTime, " +
            "sv.end_time AS activityEndTime, vo.use_time AS useTime " +
            "FROM tb_voucher_order vo " +
            "JOIN tb_voucher v ON v.id = vo.voucher_id " +
            "LEFT JOIN tb_seckill_voucher sv ON sv.voucher_id = vo.voucher_id " +
            "WHERE vo.user_id = #{userId} ORDER BY vo.create_time DESC")
    List<SeckillVoucherOrderDTO> selectUserSeckillVoucherOrders(@Param("userId") Long userId);
}
