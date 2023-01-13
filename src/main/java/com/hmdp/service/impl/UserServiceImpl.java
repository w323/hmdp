package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpRequest;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone) {

        //1、校验手机号是否合法
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2、非法
            return Result.fail("手机号格式错误！");
        }
        //3、符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        //4、保存验证码到session
//        session.setAttribute("code",code);

        //保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5、发送验证码，模拟，实际要调用其他接口
        log.info("发送验证码成功，{}", code);
        //6、返回
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1、校验验证码
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2、非法
            return Result.fail("手机号格式错误！");
        }

        //3、校验验证码
        //从redis中获取验证码进行校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);

        //Object cacheCode = session.getAttribute("code");
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            //3、不一致，报错
            return Result.fail("验证码有误！");
        }

        //4、一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();

        //5、判断用户是否存在
        if (user == null) {
            //6、不存在，创建一个用户并保存
            user = createUserWithPhone(phone);
        }

        //6、保存用户信息到redis
        //6.1、随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        //6.2、将user对象转为HashMap存储
        /**
         * 这里是在把信息保存到redis前，先将某些字段转化为字符串，避免保存到redis中时，出现
         * 类型转化异常，比如这里user的id是long型的，在转化为字符串时会出错
         */
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil
                .beanToMap(userDTO,new HashMap<>(), CopyOptions.create()
                .setIgnoreNullValue(true)
                .setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));
        //6.3、存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //6.4、设置token的有效期
        stringRedisTemplate.expire(token,LOGIN_USER_TTL, TimeUnit.MINUTES);
        //7、返回令牌给前端
        return Result.ok(token);
    }

    @Override
    public Result logout(HttpServletRequest request) {
        String token = request.getHeader("authorization");
        String tokenKey = LOGIN_USER_KEY + token;
        UserDTO user = UserHolder.getUser();

        Long isDel = stringRedisTemplate.opsForHash().delete(tokenKey, "nickName", "icon", "id");

        if(isDel > 0L) {
            return Result.ok();
        }
        return Result.fail("退出失败，有问题");
    }

    @Override
    public Result sign() {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //获取当前日期
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        //拼接key
        String key = USER_SIGN_KEY + userId + keySuffix;
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //保存到redis
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth - 1,true);

        return Result.ok();
    }

    @Override
    public Result signCount() {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //获取当前日期
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        //拼接key
        String key = USER_SIGN_KEY + userId + keySuffix;
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //获取本月到今天位置的所有签到记录，返回的是一个十进制数据
        List<Long> list = stringRedisTemplate.opsForValue().bitField(
                key, BitFieldSubCommands.create()
                        .get(BitFieldSubCommands
                                .BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0)
        );

        //循环遍历
        if(list == null || list.isEmpty()) {
            return Result.ok(0);
        }
        System.out.println(list);

        Long num = list.get(0);

        if(num == null || num == 0) {
            return Result.ok(0);
        }

        int count = 0;
        while (true) {
            if ((num & 1) == 1) {
                count++;
            }else {
                break;
            }
            num >>>=1; //无符号右移一位
        }

        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {

        //1、创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        //2、保存用户
        this.save(user);
        return user;

    }
}
