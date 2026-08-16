package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.UserTaskProgress;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

public interface UserTaskProgressMapper extends BaseMapper<UserTaskProgress> {

    /**
     * 依赖 uk_user_task_date 做原子累加；progress 永远不会超过任务目标。
     */
    @Insert("INSERT INTO user_task_progress " +
            "(user_id, task_id, progress, task_date, completed, reward_received, create_time, update_time) " +
            "VALUES (#{userId}, #{taskId}, LEAST(#{delta}, #{targetValue}), #{taskDate}, " +
            "IF(#{delta} >= #{targetValue}, 1, 0), 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) " +
            "ON DUPLICATE KEY UPDATE " +
            "completed = IF(progress + #{delta} >= #{targetValue}, 1, completed), " +
            "progress = LEAST(progress + #{delta}, #{targetValue}), update_time = CURRENT_TIMESTAMP")
    int incrementProgress(@Param("userId") Long userId,
                          @Param("taskId") Long taskId,
                          @Param("taskDate") LocalDate taskDate,
                          @Param("delta") int delta,
                          @Param("targetValue") int targetValue);

    @Select("SELECT * FROM user_task_progress WHERE id = #{id} AND user_id = #{userId} FOR UPDATE")
    UserTaskProgress selectOwnedForUpdate(@Param("id") Long id, @Param("userId") Long userId);
}
