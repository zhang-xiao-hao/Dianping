package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.sun.xml.internal.ws.resources.UtilMessages;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 封装redis工具类
 */
@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;
    // 线程池（用于缓存击穿逻辑过期方法，线程更新DB时开启另一个线程去更新），10个线程
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // 通过构造函数注入
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);


    }
    // 重建逻辑过期
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));

    }

    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        // 从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 存在，则直接返回。StrUtil.isNotBlank(json),如果json为""，是false
        if (StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        // 判断命中的是否为""值（解决缓存穿透）
        if (json != null){
            return null;
        }
        // 查数据库
        R r = dbFallback.apply(id);
        // 不存在，写入空值""
        if (r == null){
            // 将空值写入redis，预防缓存穿透问题，设置TTL，减少redis内存消耗
            stringRedisTemplate.opsForValue().set(key, "",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 存在，写入redis,设置TTL来定期删除缓存操作，再配合更新数据库时删除缓存，预防数据一致性问题，
        this.set(key, r, time, unit);
        return r;
    }


    //缓存击穿 逻辑过期解决方法
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        // 从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 未命中，返回null，（感觉逻辑过期解决方法只适用于一些特殊业务，解决不了缓存穿透。
        // 而且，逻辑过期要用的时候需要事先存好缓存,所以这个判断一直不生效，除非删除了缓存）
        if (StrUtil.isBlank(shopJson)){
            return null;
        }
        // 命中
        // 反序列化
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 没过期
        if (expireTime.isAfter(LocalDateTime.now())){
            return r;
        }
        // 过期
        String localKey = LOCK_SHOP_KEY + id;
        boolean hasLock = tryLock(localKey);
        if (hasLock) {
            // 双重校验
            shopJson = stringRedisTemplate.opsForValue().get(key);
            redisData = JSONUtil.toBean(shopJson, RedisData.class);
            r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
            expireTime = redisData.getExpireTime();
            if (expireTime.isAfter(LocalDateTime.now())){
                unlock(localKey);
                return r;
            }
            // 开启独立线程去重建缓存, 当前线程则继续执行，返回老数据（所以逻辑过期会出现
            // 数据一致性问题，但是速度块）
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查数据库
                    R r1 = dbFallback.apply(id);
                    // 重建
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException();
                }finally {
                    // 释放锁
                    unlock(localKey);
                }
            });
        }
        return r;
    }

    //缓存击穿 基于悲观锁思想的分布式锁解决方法
    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        // 从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 存在，则直接返回。StrUtil.isNotBlank(json),如果json为""，是false
        if (StrUtil.isNotBlank(shopJson)){
            return  JSONUtil.toBean(shopJson, type);
        }
        // 判断命中的是否为""值（解决缓存穿透）
        if (shopJson != null){
            return null;
        }
        // 查数据库,并进行缓存重建（乐观锁）
        // 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        R r = null;
        try {
            boolean hasLock = tryLock(lockKey);
            if (!hasLock) {
                // 休眠重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }

            // 需要再次查询redis中是否有缓存（DoubleCheck），比如有下面这种情况：线程A拿到锁并执行DB更新和redis重建，
            // 此时线程B执行到“从redis查询商铺缓存”时，A还没有更新完，所以B继续执行，执行到“获取互斥锁”时，
            // A更新完并释放锁，此时B拿到了锁，此时如果不再此进行缓存查询，B就会去查DB了，即使此时缓存中有该数据，
            String check = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(check)){
                return  JSONUtil.toBean(check, type);
            }
            // 查数据库
            r = dbFallback.apply(id);
            if (r == null){
                // 将空值写入redis，预防缓存穿透问题，设置TTL，减少redis内存消耗
                this.set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 重建
            this.set(key, JSONUtil.toJsonStr(r), time, unit);

        } catch (InterruptedException e) {
            throw new RuntimeException();
        }finally {
            // 释放锁
            unlock(lockKey);
        }
        return r;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        // 直接返回flag会进行拆箱，有可能出现空指针
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

}
