package com.hmdp.mapper;

import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.dto.BlogLikeCount;
import com.hmdp.dto.BlogLikeDelta;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface BlogMapper extends BaseMapper<Blog> {

    /** 插入成功表示批次首次执行；主键已存在时返回 0。 */
    int insertLikeSyncBatch(@Param("batchId") String batchId, @Param("itemCount") int itemCount);

    /** 在一条 SQL 中把多篇笔记的净增量累加到 liked，并将结果下限保护为 0。 */
    int batchIncrementLiked(@Param("items") List<BlogLikeDelta> items);

    /** 对账使用绝对值覆盖，只更新计数确实不一致的笔记。 */
    int batchReconcileLiked(@Param("items") List<BlogLikeCount> items);

    int deleteLikeSyncBatchesBefore(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("activeBatchId") String activeBatchId
    );
}
