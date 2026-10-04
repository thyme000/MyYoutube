package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.video.domain.dto.VideoPageQuery;
import cn.diaojiahong.myyoutube.video.domain.vo.FollowVideoVO;
import cn.diaojiahong.myyoutube.video.domain.vo.ShortsVO;
import cn.diaojiahong.myyoutube.video.domain.vo.VideoDetailVO;
import cn.diaojiahong.myyoutube.video.domain.vo.VideoListVO;

/**
 * 视频查询: 列表/详情/连播/关注流
 */
public interface IVideoQueryService {

    PageResult<VideoListVO> getVideoList(VideoPageQuery pageQuery);

    VideoDetailVO getVideoDetail(Long videoId);

    PageResult<VideoListVO> getVideoListByAuthorId(Long authorId, VideoPageQuery pageQuery);

    PageResult<VideoListVO> getMyVideoList(VideoPageQuery pageQuery);

    PageResult<ShortsVO> getShortsFeed(PageQuery pageQuery);

    PageResult<VideoListVO> getOtherUserVideoList(Long userId, PageQuery pageQuery);

    PageResult<FollowVideoVO> getFollowVideoList(PageQuery pageQuery);
}
