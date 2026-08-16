package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.UserPointsAccount;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface UserPointsAccountMapper extends BaseMapper<UserPointsAccount> {

    @Insert("INSERT INTO user_points_account (user_id, balance, update_time) " +
            "VALUES (#{userId}, #{points}, CURRENT_TIMESTAMP) " +
            "ON DUPLICATE KEY UPDATE balance = balance + #{points}, update_time = CURRENT_TIMESTAMP")
    int addPoints(@Param("userId") Long userId, @Param("points") int points);
}
