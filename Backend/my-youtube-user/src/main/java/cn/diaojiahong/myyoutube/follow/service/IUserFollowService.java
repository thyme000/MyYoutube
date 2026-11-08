package cn.diaojiahong.myyoutube.follow.service;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
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

    void follow(Long authorId);

    void cancelFollow(Long authorId);

    PageResult<UserRelationshipVO> getUserFollowList(PageQuery pageQuery);

    Boolean isFollow(Long authorId);

    Boolean interrelated(Long userId, List<Long> memberIds);

    /** 粉丝数权威值, 供 user 服务对 user.fans 做对账覆盖 */
    Long countFans(Long followeeId);

    /** 关注数权威值, 供 user 服务对 user.attention_count 做对账覆盖 */
    Long countAttention(Long followerId);

    PageResult<UserRelationshipVO> getFansList(PageQuery pageQuery);

    List<Long> getFollowIds(Long userId);

    List<Boolean> isFollowOnList(List<Long> authorIds, Long userId);

    void removeFan(Long followerId);

    PageResult<UserRelationshipVO> getInterrelated(PageQuery pageQuery);
    }
