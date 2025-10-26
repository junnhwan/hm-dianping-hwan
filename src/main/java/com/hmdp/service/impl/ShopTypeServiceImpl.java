package com.hmdp.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_TYPE_TTL;

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
    public Result queryShopType() {
        String cacheShopTypeKey = CACHE_TYPE_KEY;
        // 1. 查询redis缓存是否存在
        String shopTypeJson = stringRedisTemplate.opsForValue().get(cacheShopTypeKey);
        // 2.存在直接返回
        if(StrUtil.isNotBlank(shopTypeJson)) {
            List<ShopType> typeList = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(typeList);
        }
        // 3. 不存在则查数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        // 4. 数据库不存在则返回错误信息
        if(CollUtil.isEmpty(typeList)) {   // 这里注意：MP不会返回空值，所以不用判null，用hutool的CollUtil即可
            return Result.fail("店铺类型不存在！");
        }
        // 5. 数据库存在则写入redis缓存并返回
        stringRedisTemplate.opsForValue().set(cacheShopTypeKey, JSONUtil.toJsonStr(typeList),
                                                CACHE_TYPE_TTL, TimeUnit.MINUTES);
        return Result.ok(typeList);
    }
}
