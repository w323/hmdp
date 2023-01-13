package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    //创建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);
        cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //互斥锁实现缓存击穿
        Shop shop = queryWithLogicalExpire(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    private Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.从redis中查询商铺id缓存
        String jsonBean = stringRedisTemplate.opsForValue().get(key);
        //2.若不存在，直接将结果返回
        if (StrUtil.isBlank(jsonBean)) {
            return null;
        }
        //3、命中，先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(jsonBean,RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //4、判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //4.1、未过期，直接返回店铺信息
            return shop;
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
                    //重建缓存
                    this.saveShopToRedis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //5.4、返回过期的商铺信息

        return shop;

    }

    //锁互斥
    private Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.从redis中查询商铺id缓存
        String jsonBean = stringRedisTemplate.opsForValue().get(key);
        //2.若存在，直接将结果返回
        if (StrUtil.isNotBlank(jsonBean)) {
            return JSONUtil.toBean(jsonBean, Shop.class);

        }
        if (jsonBean != null) {
            return null;
        }

        //3、实现缓存重建
        //3.1、获取互斥锁
        String lockKey = "lock:shop:" + id;
        boolean b = tryLock(lockKey);
        Shop shop = null;
        try {
            //3.2、判断是否获取成功
            if (!b) {
                //3.2、失败则睡眠重试
                try {
                    TimeUnit.MILLISECONDS.sleep(50);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                return queryWithMutex(id);
            }
            //4.不存在，通过数据库查询信息
            shop = getById(id);
            //5.数据库中不存在，返回错误
            if (shop == null) {
                //写入redis，避免再次查询数据库，给数据库造成压力
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);

                return null;
            }
            //6.存在，先保存到redis中，在返回
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            //7、释放锁
            unlock(lockKey);
        }
        return shop;

    }


    //缓存穿透
    private Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.从redis中查询商铺id缓存
        String jsonBean = stringRedisTemplate.opsForValue().get(key);
        //2.若存在，直接将结果返回
        if (StrUtil.isNotBlank(jsonBean)) {
            return JSONUtil.toBean(jsonBean, Shop.class);

        }

        if (jsonBean != null) {
            return null;
        }
        //3.不存在，通过数据库查询信息
        Shop shop = getById(id);
        //4.数据库中不存在，返回错误
        if (shop == null) {
            //写入redis，避免再次查询数据库，给数据库造成压力
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);

            return null;
        }
        //5.存在，先保存到redis中，在返回
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }

    /**
     * 利用redis的string类型，setnx,如果数据存在，就不会再改变
     *
     * @param key redis的key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //自动彩箱可能会存在空指针
        return BooleanUtil.isTrue(aBoolean);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    public void saveShopToRedis(Long id, Long expireSeconds) {
        //1、查询店铺数据
        Shop shop = getById(id);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));

    }

    @Override
    @Transactional //开启事务，确保数据库和redis中的数据一致性
    public Result update(Shop shop) {

        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }

        //先跟新数据库
        updateById(shop);
        //在删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {

        //1.判断是否需要根据坐标去查询店铺
        if(x == null || y == null) {
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        //2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        //3.查询redis、按照距离排序、分页、结果：shopId，distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        //对结果进行物理截取分页
        if(results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        //内容数小于from直接放回空
        if (content.size() <= from) {
            return Result.ok(Collections.emptyList());
        }
        //截取from到end部分
        ArrayList<Long> ids = new ArrayList<>(content.size());
        Map<String,Distance> distanceMap = new HashMap<>(content.size());

        content.stream().skip(from).forEach(result -> {
            //获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            //获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr,distance);
        });
        //根据id查询shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("order by field(id," + idStr + ")").list();
        //把距离保存到shop
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }

        return Result.ok(shops);
    }
}
