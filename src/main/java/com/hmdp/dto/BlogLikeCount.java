package com.hmdp.dto;

/** 对账时一篇笔记应有的绝对点赞数。 */
public class BlogLikeCount {

    private final Long blogId;
    private final Long liked;

    public BlogLikeCount(Long blogId, Long liked) {
        this.blogId = blogId;
        this.liked = liked;
    }

    public Long getBlogId() {
        return blogId;
    }

    public Long getLiked() {
        return liked;
    }
}
