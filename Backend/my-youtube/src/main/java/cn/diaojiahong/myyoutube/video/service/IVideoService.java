package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.mq.message.VideoCoverMessage;
import cn.diaojiahong.myyoutube.video.domain.dto.VideoDTO;
import cn.diaojiahong.myyoutube.video.domain.dto.VideoPageQuery;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.domain.vo.MyVideoListVO;
import cn.diaojiahong.myyoutube.video.domain.vo.ShortsVO;
import cn.diaojiahong.myyoutube.video.domain.vo.VideoDetailVO;
import cn.diaojiahong.myyoutube.video.domain.vo.VideoListVO;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 视频信息表 服务类
 * </p>
 *
 * @author thyme
 * @since 2026-03-22
 */
public interface IVideoService extends IService<Video> {

    Result<String> publishVideo(VideoDTO videoDTO);

    Result<PageResult<VideoListVO>> getVideoList(VideoPageQuery pageQuery);

    Result<VideoDetailVO> getVideoDetail(Long videoId);

    Result<PageResult<VideoListVO>> getVideoListByAuthorId(Long authorId, VideoPageQuery pageQuery);

    Result<PageResult<MyVideoListVO>> getMyVideoList(VideoPageQuery pageQuery);

    Result<String> generateVideoCover(VideoCoverMessage videoCoverMessage);

    Result<PageResult<ShortsVO>> getShortsFeed(PageQuery pageQuery);
}
