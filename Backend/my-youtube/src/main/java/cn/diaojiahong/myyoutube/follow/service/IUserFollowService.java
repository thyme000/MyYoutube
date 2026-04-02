package cn.diaojiahong.myyoutube.follow.service;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.follow.domain.po.UserFollow;
import cn.diaojiahong.myyoutube.follow.domain.vo.UserFanVO;
import cn.diaojiahong.myyoutube.follow.domain.vo.UserFollowVO;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 用户关注关系表 服务类
 * </p>
 *
 * @author thyme
 * @since 2026-04-01
 */
public interface IUserFollowService extends IService<UserFollow> {

    Result<Void> follow(Long authorId);

    Result<Void> cancelFollow(Long authorId);

    Result<PageResult<UserFollowVO>> getUserFollowList(PageQuery pageQuery);

    Result<Boolean> isFollow(Long authorId);

    Result<PageResult<UserFanVO>> getFansList(PageQuery pageQuery);

    List<Long> getFollowIds(Long userId);

    Map<Long, Boolean> isFollowOnList(List<Long> authorIds, Long userId);
}
