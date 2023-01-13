package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {


    //序列号的位数
    private static final int COUNT_BITS = 32;

    private static final long BEGIN_TIMESTAMP = 1640995200L;

    private final StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix) {
        //生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowTime = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowTime - BEGIN_TIMESTAMP;

        //2.生成序列号
        //2.1、获取当前日期，精确到天
        String format = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //2.2、日增长
        long count  = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + format);

        return timeStamp << COUNT_BITS | count;
    }

}
