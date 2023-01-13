package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private FollowMapper followMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, boolean isFallow) {
        //1.获取登陆的用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        //2.判断是关注还是取关
        if (isFallow) {
            //3.关注，增加数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if (isSuccess) {
                //把关注的用户id放入redis集合中，sadd userId  followUserId

                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            //4.取关，删除数据 delete from tb_follow where userId = ? and followId = ?
            boolean b = followMapper.causeFollow(followUserId, userId);
            if (b) {
                stringRedisTemplate.opsForSet().remove(key, followUserId + "");
            }
        }

        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        int notice = followMapper.isNotice(followUserId, userId);
        return Result.ok(notice > 0);
    }

    @Override
    public Result followCommons(Long id) {

        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        //求交集
        String key2 = "follows:" + id;
        Set<String> commonFollow = stringRedisTemplate.opsForSet().intersect(key, key2);
        //解析id
        if (commonFollow != null) {
            List<Long> list = commonFollow.stream().map(Long::valueOf).collect(Collectors.toList());
            //查询用户
            List<UserDTO> users = userService.listByIds(list)
                    .stream()
                    .map(user ->BeanUtil.copyProperties(user, UserDTO.class))
                    .collect(Collectors.toList());
            return Result.ok(users);
        }
        return Result.ok();
    }


    /*@Override
    public void causeFollow(Long followId, Long userId) {
       getBaseMapper().causeFollow(followId,userId);
    }

    @Override
    public int isNotice(Long followId, Long userId) {
        return getBaseMapper().isNotice(followId, userId);
    }*/


}
