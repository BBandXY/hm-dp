package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.PointsChangeLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface PointsChangeLogMapper extends BaseMapper<PointsChangeLog> {

    @Insert("INSERT IGNORE INTO points_change_log " +
            "(request_id, user_id, task_progress_id, points, source, create_time) " +
            "VALUES (#{requestId}, #{userId}, #{taskProgressId}, #{points}, #{source}, CURRENT_TIMESTAMP)")
    int insertIgnore(@Param("requestId") String requestId,
                     @Param("userId") Long userId,
                     @Param("taskProgressId") Long taskProgressId,
                     @Param("points") int points,
                     @Param("source") String source);
}
