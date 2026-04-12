package cn.diaojiahong.myyoutube.follow.service;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.follow.domain.po.UserFollow;
import cn.diaojiahong.myyoutube.follow.domain.vo.UserRelationshipVO;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

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

    Result<PageResult<UserRelationshipVO>> getUserFollowList(PageQuery pageQuery);

    Result<Boolean> isFollow(Long authorId);

    Boolean interrelated(Long userId, List<Long> memberIds);

    Result<PageResult<UserRelationshipVO>> getFansList(PageQuery pageQuery);

    List<Long> getFollowIds(Long userId);

    List<Boolean> isFollowOnList(List<Long> authorIds, Long userId);

    Result<Void> removeFan(Long followerId);

    Result<PageResult<UserRelationshipVO>> getInterrelated(PageQuery pageQuery);
}
