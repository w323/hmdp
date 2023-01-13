package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    Result follow(Long followUserId, boolean isFallow);

    Result isFollow(Long followUserId);

    Result followCommons(Long id);

    //void causeFollow(Long followId, Long userId);

    //int isNotice(Long followId, Long userId);
}