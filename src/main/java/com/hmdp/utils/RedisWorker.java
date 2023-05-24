package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisWorker {
    // 开始时间戳
    private static final long BEGIN_TIMESTAMP = 1683676800L; // 2023/5/10

    private StringRedisTemplate stringRedisTemplate;
    // 序列号的位数
    private static final int COUNT_BITS = 32;
    public RedisWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 生成全局id，保证在高并发情况下的id唯一性、高效性、规律难寻性等（优惠券秒杀）
     * @param keyPrefix keyPrefix
     * @return 全局唯一id
     */
    public long nextId(String keyPrefix){
        // 1、生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2、生成序列号（高并发情况下并不能保证时间戳唯一，所以还要redis生成序列号（自增命令具有原子性））
        // 2.1、获取当前日期（每天为一个新key，以防超出redis上限以及方便统计订单量）
        String data = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2 自增长（不会空指针，如果没有该key，会自动创建）
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + data);

        // 3、拼接并返回（long为8个字节，第一位为符号位，时间戳使用高31位，序列号为低32位）
        return timestamp << COUNT_BITS | count;
    }

}


