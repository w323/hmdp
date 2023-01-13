package com.hmdp.mapper;

import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface FollowMapper extends BaseMapper<Follow> {

    //取关
    boolean causeFollow(@Param("followId") Long followId, @Param("userId") Long userId);

    //是否关注
    int isNotice(@Param("followId") Long followId,@Param("userId")  Long userId);
}
