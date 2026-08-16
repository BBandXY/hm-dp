package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.VoucherGrantLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface VoucherGrantLogMapper extends BaseMapper<VoucherGrantLog> {

    @Insert("INSERT IGNORE INTO voucher_grant_log " +
            "(request_id, user_id, voucher_id, task_progress_id, source, status, create_time, update_time) " +
            "VALUES (#{requestId}, #{userId}, #{voucherId}, #{taskProgressId}, #{source}, " +
            "'CREATED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")
    int insertRequestIgnore(@Param("requestId") String requestId,
                            @Param("userId") Long userId,
                            @Param("voucherId") Long voucherId,
                            @Param("taskProgressId") Long taskProgressId,
                            @Param("source") String source);

    @Select("SELECT * FROM voucher_grant_log WHERE request_id = #{requestId} FOR UPDATE")
    VoucherGrantLog selectByRequestIdForUpdate(@Param("requestId") String requestId);

    @Update("UPDATE voucher_grant_log SET status = 'PENDING', fail_reason = NULL, " +
            "update_time = CURRENT_TIMESTAMP WHERE request_id = #{requestId} AND status = 'CREATED'")
    int markPendingIfCreated(@Param("requestId") String requestId);
}
