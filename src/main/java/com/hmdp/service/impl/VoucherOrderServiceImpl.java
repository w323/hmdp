package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    private IVoucherOrderService proxy;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setResultType(Long.class);
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
    }

    private final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    public void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {

        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                //从消息队列中获取消息 XREADGROUP GROUP g1 c1 COUNT 1  BLOCK 2000 STREAMS stream.orders
                List<MapRecord<String, Object, Object>> mapRecords = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(queueName, ReadOffset.lastConsumed()));
                //判断是否取成功
                if(mapRecords == null || mapRecords.isEmpty()) {
                    //获取失败，继续下次获取
                    continue;
                }
                //解析消息中的订单信息
                MapRecord<String, Object, Object> record = mapRecords.get(0);
                Map<Object, Object> value = record.getValue();
                //获取成功，下单


            }
        }
    }

    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long id = UserHolder.getUser().getId();
        //获取订单
        long orderId = redisIdWorker.nextId("order");
        //执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                id.toString(), voucherId.toString(), String.valueOf(orderId)
        );

        int i = result.intValue();
        if(i != 0) {
            //代表没有购买资格
            return Result.fail(i==1?"库存不足":"不能重复下单");
        }
        //获取代理对象
        proxy = (IVoucherOrderService)AopContext.currentProxy();
        //返回订单id
        return Result.ok(orderId);
    }

    @Override
    public Result createVoucherOrder(Long voucherId) {
        //获取用户
        UserDTO user = UserHolder.getUser();
        //执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), user.getId().toString()
        );
        if (result != 0) {
            return Result.fail(result == 1 ? "库存不足" : "不能重复下单");
        }

        //下单，将信息保存到阻塞队列
        long order = redisIdWorker.nextId("order");
        //todo
        return Result.ok(0);
    }

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        //1、查询优惠卷
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        //2、查询活动是否开始
        boolean flag = seckillVoucher.getBeginTime().isAfter(LocalDateTime.now());//活动时间大于现在时间
        if (flag) {
            return Result.fail("活动未开始");
        }
        //3、查询活动是否结束
        boolean flag1 = seckillVoucher.getEndTime().isAfter(LocalDateTime.now());
        if (!flag1) {
            return Result.fail("活动已结束");
        }
        //4、判断是否有库存
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();

        //SimpleRedisLock simpleRedisLock = new SimpleRedisLock(redisTemplate, "order:" + userId);
        RLock lock = redissonClient.getLock("order:" + userId);
        boolean isLock = lock.tryLock();
        if(!isLock) {
            return Result.fail("只能下一单");
        }

        try {
            synchronized (userId.toString().intern()) {
                //获取代理对象（事务）
                IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
                return proxy.createVoucherOrder(voucherId);
            }
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //一人一单
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("用户只能下一单");
        }

        //5、扣减库存
        //使用cas确保库存不会被超卖
        boolean success = seckillVoucherService.update().
                setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("库存不足");
        }
        //6、创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //6.1、用户id

        voucherOrder.setUserId(userId);
        //6.2、订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setVoucherId(orderId);
        //6.3、代金卷id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        //7、返回订单id
        return Result.ok(orderId);
    }*/
}
