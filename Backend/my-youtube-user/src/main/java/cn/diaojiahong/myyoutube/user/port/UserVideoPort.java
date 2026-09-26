package cn.diaojiahong.myyoutube.user.port;

import cn.diaojiahong.myyoutube.user.port.dto.VideoSummary;

import java.util.Collection;
import java.util.Set;
import java.util.List;
import java.util.Map;

/**
 * 用户侧视频查询端口: 由 video 模块提供实现, user 模块不直接依赖 video
 */
public interface UserVideoPort {

    /**
     * 按给定 id 顺序查询视频摘要(库中已删除的会被过滤)
     */
    List<VideoSummary> listByIdsOrdered(Set<String> ids);

    /** 用户对一批视频的点赞状态 */
    Map<Long, Boolean> likedMap(Long userId, Collection<String> videoIds);

    /** 用户对一批作者的关注状态 */
    Map<Long, Boolean> followedMap(Long userId, Collection<Long> authorIds);
}
