package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryAll() {
        //查缓存
        String shoptype = stringRedisTemplate.opsForValue().get("cache:shoptype:list");
        //判断
        if (StrUtil.isNotBlank(shoptype)){
            List<ShopType> shopTypeList = JSONUtil.toList(shoptype, ShopType.class);
            return Result.ok(shopTypeList);
        }
        //存在 返回
        //不存在查数据库
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        //不存在 返回错误
        if (shopTypeList == null){
            return Result.fail("没有店铺类型信息");
        }
        //存在写redis
        stringRedisTemplate.opsForValue().set("cache:shoptype:list",
                JSONUtil.toJsonStr(shopTypeList), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //返回
        return Result.ok(shopTypeList);
    }
}
