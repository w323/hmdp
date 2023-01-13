package com.hmdp.controller;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.*;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @GetMapping("list")
    public Result queryTypeList() {
        List<String> shopList = stringRedisTemplate.opsForList().getOperations().boundListOps("cache:shopType").range(0, -1);
        List<ShopType> typeList = new ArrayList<>();
        if(shopList != null && shopList.size() > 0) {
            for (String s : shopList) {
                String s1 = JSONUtil.toJsonStr(s);
                ShopType shopType = JSONUtil.toBean(s1, ShopType.class);
                typeList.add(shopType);
            }
            return Result.ok(typeList);
        }

        typeList = typeService
                .query().orderByAsc("sort").list();

        List<String> list = new ArrayList<>();
        for (ShopType shopType : typeList) {
            list.add(JSONUtil.toJsonStr(shopType));
        }
        stringRedisTemplate.opsForList().leftPushAll("cache:shopType",list);

        return Result.ok(typeList);
    }
}
