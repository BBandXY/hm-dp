package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
@Disabled("手工数据初始化测试，需要本地 MySQL 和 Redis")
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    StringRedisTemplate stringRedisTemplate;
    /**
     * 预热所有店铺数据到 Redis（逻辑过期方案）
     * 手动运行此测试方法即可
     */
    @Test
    void preheatShopCache() {
        List<Shop> shops = shopService.list();
        if (shops == null || shops.isEmpty()) {
            System.out.println("数据库中没有店铺数据");
            return;
        }
        for (Shop shop : shops) {
            cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + shop.getId(), shop, 30L, TimeUnit.MINUTES);
        }
        System.out.println("预热完成，共写入 " + shops.size() + " 条店铺数据");
    }
    @Test
    void loadShopData(){
        //查询信息
        List<Shop> list = shopService.list();
        //分组
        // 2.把店铺分组，按照typeId分组，typeId一致的放到一个集合
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));

        // 3.分批完成写入Redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 3.1.获取类型id
            Long typeId = entry.getKey();
            String key = "shop:geo:" + typeId;
            // 3.2.获取同类型的店铺的集合
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());

            // 3.3.写入redis GEOADD key 经度 纬度 member
            for (Shop shop : value) {
                // 单条插入版本（被注释）
                // stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
                // 组装批量的GeoLocation对象
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            }
            // 批量一次性写入Redis，减少网络IO，性能更高
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }


    @Test
    void testHyperLogLog() {
        // 准备数组，装用户数据
        String[] users = new String[1000];
        // 数组角标
        int index = 0;
        for (int i = 1; i <= 1000000; i++) {
            // 赋值
            users[index++] = "user_" + i;
            // 每1000条发送一次
            if (i % 1000 == 0) {
                index = 0;
                stringRedisTemplate.opsForHyperLogLog().add("hll1", users);
            }
        }
        // 统计数量
        Long size = stringRedisTemplate.opsForHyperLogLog().size("hll1");
        System.out.println("size = " + size);
    }

}
