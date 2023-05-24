package com.hmdp.utils;

public class RedisConstants {
    // 登录验证码
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    // 登录token
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 30L;
    // shop空值TTL（缓存穿透）
    public static final Long CACHE_NULL_TTL = 2L;
    // shop
    public static final Long CACHE_SHOP_TTL = 1800L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    // shop type
    public static final String CACHE_SHOP_Type_KEY = "cache:shop:type";
    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    // 秒杀订单分布式锁
    public static final String SECKILL_ORDER_LOCK_KEY = "seckill:stock:";

    // 秒杀库存（秒杀优化）
    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    // 笔记点赞
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
