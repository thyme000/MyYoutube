package cn.diaojiahong.myyoutube.follow.mapper;

import cn.diaojiahong.myyoutube.follow.domain.po.UserFollow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 * 用户关注关系表 Mapper 接口
 * </p>
 *
 * @author thyme
 * @since 2026-04-01
 */
public interface UserFollowMapper extends BaseMapper<UserFollow> {

    int followOrRestore(@Param("id") Long id, @Param("followerId") Long followerId, @Param("followeeId") Long followeeId);

    int cancelFollow(@Param("followerId") Long followerId, @Param("followeeId") Long followeeId);
}
