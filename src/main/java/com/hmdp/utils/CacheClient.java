package com.hmdp.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;


    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    //设置逻辑过期
    public void setLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从redis中查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.若存在，直接将结果返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }
        //3.不存在，通过数据库查询信息
        R r = dbFallback.apply(id);
        //4.数据库中不存在，返回错误
        if (r == null) {
            //写入redis，避免再次查询数据库，给数据库造成压力
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);

            return null;
        }
        //5.存在，先保存到redis中，在返回
        this.set(key,r,time,unit);

        return r;
    }

    public <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time,TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从redis中查询商铺id缓存
        String jsonBean = stringRedisTemplate.opsForValue().get(key);
        //2.若不存在，直接将结果返回
        if (StrUtil.isBlank(jsonBean)) {
            return null;
        }
        //3、命中，先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(jsonBean,RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //4、判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //4.1、未过期，直接返回店铺信息
            return r;
        }


        //4.2、已过期，需要缓存重建
        //5、缓存重建
        //5.1、获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        //5.2、判断锁是否获取成功
        boolean b = this.tryLock(lockKey);

        if(b) {
            //5.3、成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    // 写入redis
                    this.setLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //5.4、返回过期的商铺信息

        return r;

    }

    private boolean tryLock(String key) {
        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //自动彩箱可能会存在空指针
        return BooleanUtil.isTrue(aBoolean);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }


}
