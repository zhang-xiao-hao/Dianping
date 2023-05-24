package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 一人一单功能的锁
 */
public class SimpleRedisLock implements ILock{
    private String name;
    private StringRedisTemplate stringRedisTemplates;
    private static final String KET_PREFIX = "lock:";
    // static final 
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    // 静态加载unlock lua脚本，避免每次执行脚本时都要重新读取和解析脚本文件的开销
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT; //Long为返回值类型
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplates) {
        this.name = name;
        this.stringRedisTemplates = stringRedisTemplates;
    }

    @Override
    public boolean tryLock(Long timeoutSec) {
         /*为什么要拼接一个字符串UUID？当线程A获取到锁时，线程A发生了阻塞，导致redis中lock
         的ttl到期了还没有释放锁，此时，其它线程就会获取到锁，而线程A苏醒后执行完成又主动释放锁，
         其它线程的锁就被释放了。这里通过释放锁时判断当前锁是否为当前线程的锁来解决，判断时，
         如果只有threadId，那么在分布式系统下，lock有可能是重复的，导致上面问题仍有可能存在。
         所以要拼接UUID（这个UUID只在不同系统中不同，因为它是static final）来防止分布式系统下lock重复。*/
        // 总结：static final UUID保证分布式系统之间的上述问题，threadId保证单个系统内的上述问题，
        String lock = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplates.opsForValue()
                .setIfAbsent(KET_PREFIX + name, lock, timeoutSec, TimeUnit.DAYS);
        return BooleanUtil.isTrue(success);
    }

    @Override
    public void unlock() {
        // stringRedisTemplates.execute执行lua脚本(把判断和释放归为一个原子操作，
        // 预防释放过程中阻塞导致的删除其它线程的锁的问题)
        stringRedisTemplates.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KET_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }

}
