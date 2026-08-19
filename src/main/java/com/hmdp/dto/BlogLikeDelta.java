package com.hmdp.dto;

/** 一篇笔记在某个落库批次内的点赞净变化量。 */
public class BlogLikeDelta {

    private final Long blogId;
    private final Long delta;

    public BlogLikeDelta(Long blogId, Long delta) {
        this.blogId = blogId;
        this.delta = delta;
    }

    public Long getBlogId() {
        return blogId;
    }

    public Long getDelta() {
        return delta;
    }
}
