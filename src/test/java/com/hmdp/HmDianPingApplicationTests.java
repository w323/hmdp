package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;


@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private IShopService service;

    @Resource
    private RedisIdWorker redisIdWorker;

    private final ExecutorService executorService = Executors.newFixedThreadPool(500);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void testIdWorker() throws InterruptedException {

        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long start = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            executorService.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("耗时：" + (end - start));
    }

    @Test
    public void testSave() throws InterruptedException {

        AtomicInteger count = new AtomicInteger();

        CountDownLatch latch = new CountDownLatch(20);

        Runnable task = () -> {
            for (int i = 0; i < 10; i++) {
                System.out.println(count.getAndIncrement());
            }
            latch.countDown();
        };

        for (int i = 0; i < 20; i++) {
            executorService.submit(task);
        }
        latch.await();
        System.out.println("========");
    }

    public static class Sigleton{
        private static Sigleton sigleton;

        private Sigleton() {
        }

        public static Sigleton getSigleton() {
            if(sigleton == null) {
                sigleton = new Sigleton();
            }
            return sigleton;
        }
    }

    @Test
    public void test() {

        for (int i = 0; i < 100; i++) {
            new Thread(()->{
                Sigleton sigleton = Sigleton.getSigleton();
                System.out.println(Thread.currentThread().getName() + "\t" + sigleton);
            },""+i).start();
        }

    }


    @Test
    public void loadShopData() {
        //1.查询店铺信息
        List<Shop> shops = service.list();
        //2.对店铺进行分组，按照typeId分组
        Map<Long,List<Shop>> map = shops.stream().collect(Collectors.groupingBy(Shop::getTypeId));

        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            //获取店铺typeId
            Long typeId = entry.getKey();
            String key = "shop:geo:" + typeId;
            //获取同一类型店铺的集合
            List<Shop> shopList = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shopList.size());
            for (Shop shop : shopList) {
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),
                        new Point(shop.getX(),shop.getY())));
            }

            stringRedisTemplate.opsForGeo().add(key,locations);
        }
    }

    @Test
    public void testHyperLogLog() {
        String[] values = new String[1000];

        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "user_" + i;
            if(j == 999) {
                stringRedisTemplate.opsForHyperLogLog().add("hl2",values);
            }
        }

        //统计数量
        Long size = stringRedisTemplate.opsForHyperLogLog().size("hl2");
        System.out.println(size / 1000000.0D);
    }


}
