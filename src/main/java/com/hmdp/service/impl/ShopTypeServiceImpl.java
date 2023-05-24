package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_Type_KEY;


@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ShopTypeMapper shopTypeMapper;
    @Override
    public List<ShopType> queryTypeList() {
        // 查redis
        List<String> jsonList = stringRedisTemplate.opsForList().range(CACHE_SHOP_Type_KEY, 0, -1);
        if (jsonList != null && !jsonList.isEmpty()){
            // 反序列化为List<ShopType>对象
            List<ShopType> typeList = new ArrayList<>();
            for (String jsonStr : jsonList) {
                ShopType shop = JSONUtil.toBean(jsonStr, ShopType.class);
                typeList.add(shop);
            }
            return typeList;
        }
        // 查数据库
        List<ShopType> typeList = shopTypeMapper.selectList(new QueryWrapper<ShopType>().orderByAsc("sort"));
        if (typeList == null){
            return null;
        }
        // redis缓存，转为json存储list
        List<String> jsonListToSaved = new ArrayList<>();
        for (ShopType type : typeList) {
            String jsonStr = JSONUtil.toJsonStr(type);
            jsonListToSaved.add(jsonStr);
        }
        stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOP_Type_KEY, jsonListToSaved);

        return typeList;
    }
}
