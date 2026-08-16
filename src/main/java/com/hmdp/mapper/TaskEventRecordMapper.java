package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.TaskEventRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

public interface TaskEventRecordMapper extends BaseMapper<TaskEventRecord> {

    @Insert("INSERT IGNORE INTO task_event_record " +
            "(user_id, task_code, biz_id, task_date, create_time) " +
            "VALUES (#{userId}, #{taskCode}, #{bizId}, #{taskDate}, CURRENT_TIMESTAMP)")
    int insertIgnore(@Param("userId") Long userId,
                     @Param("taskCode") String taskCode,
                     @Param("bizId") String bizId,
                     @Param("taskDate") LocalDate taskDate);
}
