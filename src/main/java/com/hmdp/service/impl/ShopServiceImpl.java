package com.hmdp.service.impl;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import lombok.NonNull;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.ErrorMessageConstants.SHOP_ID_NOTNULL_MESSAGE;
import static com.hmdp.utils.ErrorMessageConstants.SHOP_NOT_EXIST_MESSAGE;
import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.DEFAULT_PAGE_SIZE;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    /**
     * 缓存穿透：1）将空值写入redis √
     *          2）布隆过滤器
     *                ......
     * 决缓存击穿:乐观锁/逻辑过期
     * @param id 商铺id
     * @return 商铺信息
     */
    @Override
    public Result queryById(Long id) {
        // 缓存穿透
//        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.SECONDS);
        // 缓存击穿：分布式锁 同时也包含了缓存穿透解决方法
        Shop shop = cacheClient.queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.SECONDS);
        // 缓存击穿：逻辑过期
//        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.SECONDS);
        if (shop == null) {
            return Result.fail(SHOP_NOT_EXIST_MESSAGE);
        }
        return Result.ok(shop);
    }

    /**
     * 更新，同时删除缓存，做数据一致性。
     * @param shop 更新数据
     * @return ok/fail
     */
    @Override
    @Transactional //数据库更新和缓存删除为一个原子事务
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail(SHOP_ID_NOTNULL_MESSAGE);
        }
        // 更新数据库（先更新数据库后删除缓存比先删缓存后更新数据库更好）
        updateById(shop);
        // 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
    // 按距离查询商家，redis geo
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 不需要根据距离查询
        if (x == null || y == null){
            Page<Shop> page = query().eq("type_id", typeId)
                    .page(new Page<>(current, DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        // 计算分页参数
        int from = (current - 1) * DEFAULT_PAGE_SIZE;
        int end = current * DEFAULT_PAGE_SIZE;
        // 查询redis，按距离排序、分页
        String key = SHOP_GEO_KEY + typeId;
        // 搜索在坐标(x,y)的5km范围内带距离信息的数据
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key, GeoReference.fromCoordinate(x, y), new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));

        // 解析出shopId
        if (results == null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from){
            // 没有下一页了
            return Result.ok(Collections.emptyList());
        }
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        // 截取from到end（逻辑分页）
        List<Long> ids = new ArrayList<>(list.size());
        list.stream().skip(from).forEach(result -> {
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 根据id查询shop(保持sortSet中的顺序)
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            // distance不是数据库中的字段
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }


}
