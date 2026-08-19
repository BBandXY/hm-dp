package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.constants.MarketingConstants;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.service.marketing.TaskEventService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/** 探店笔记服务。任务事件采用降级门面，不扩大营销故障影响。 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Resource
    private TaskEventService taskEventService;

    @Resource
    private BlogLikeRedisService blogLikeRedisService;

    @Override
    public Result queryHotBlog(Integer current) {
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        records.forEach(blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
            fillRealTimeLikeCount(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        queryBlogUser(blog);
        isBlogLiked(blog);
        fillRealTimeLikeCount(blog);
        return Result.ok(blog);
    }

    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        BlogLikeRedisService.ToggleResult toggleResult = blogLikeRedisService.toggleLike(id, userId, null);

        if (toggleResult == BlogLikeRedisService.ToggleResult.NEEDS_INITIALIZATION) {
            // Redis 冷启动后仅首次操作该笔记需要读库，后续点赞请求只执行一次 Lua。
            Blog persistedBlog = getById(id);
            if (persistedBlog == null) {
                return Result.fail("笔记不存在");
            }
            Integer initialLiked = persistedBlog.getLiked() == null ? 0 : persistedBlog.getLiked();
            toggleResult = blogLikeRedisService.toggleLike(id, userId, initialLiked);
            if (toggleResult == BlogLikeRedisService.ToggleResult.NEEDS_INITIALIZATION) {
                throw new IllegalStateException("点赞计数初始化失败，请稍后重试");
            }
        }

        // 营销任务只关心从“未点赞”到“已点赞”的状态变化，取消点赞不产生事件。
        if (toggleResult == BlogLikeRedisService.ToggleResult.LIKED) {
            taskEventService.recordSafely(
                    userId, MarketingConstants.TASK_LIKE_BLOG, "blog:" + id
            );
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(BLOG_LIKED_KEY + id, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        List<UserDTO> users = userService.query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }

    @Override
    public Result savaBlog(Blog blog) {
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        if (!save(blog)) {
            return Result.fail("新增笔记失败");
        }

        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        for (Follow follow : follows) {
            stringRedisTemplate.opsForZSet().add(
                    FEED_KEY + follow.getUserId(), blog.getId().toString(), System.currentTimeMillis()
            );
        }
        taskEventService.recordSafely(
                user.getId(), MarketingConstants.TASK_PUBLISH_BLOG, blog.getId().toString()
        );
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(FEED_KEY + userId, 0, max, offset, 2);
        if (tuples == null || tuples.isEmpty()) {
            return Result.ok();
        }

        long minTime = 0;
        int sameTimeOffset = 1;
        List<Long> ids = new ArrayList<>(tuples.size());
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            ids.add(Long.valueOf(tuple.getValue()));
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                sameTimeOffset++;
            } else {
                minTime = time;
                sameTimeOffset = 1;
            }
        }

        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Blog blog : blogs) {
            queryBlogUser(blog);
            isBlogLiked(blog);
            fillRealTimeLikeCount(blog);
        }

        ScrollResult result = new ScrollResult();
        result.setList(blogs);
        result.setOffset(sameTimeOffset);
        result.setMinTime(minTime);
        return Result.ok(result);
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return;
        }
        Double score = stringRedisTemplate.opsForZSet().score(
                BLOG_LIKED_KEY + blog.getId(), user.getId().toString()
        );
        blog.setIsLike(score != null);
    }

    private void queryBlogUser(Blog blog) {
        User user = userService.getById(blog.getUserId());
        if (user != null) {
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
        }
    }

    /** 数据库异步落库期间，详情页仍展示 Redis 中的实时逻辑点赞数。 */
    private void fillRealTimeLikeCount(Blog blog) {
        Long logicalCount = blogLikeRedisService.getLogicalLikeCount(blog.getId());
        if (logicalCount != null) {
            long safeCount = Math.max(0L, Math.min(logicalCount, Integer.MAX_VALUE));
            blog.setLiked((int) safeCount);
        }
    }
}
