package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.mq.message.VideoCoverMessage;
import cn.diaojiahong.myyoutube.video.domain.dto.*;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.domain.vo.*;
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

    /**
     * 事务消息的本地事务: 视频入库 + 标签异步保存
     */
    void persistNewVideo(Video video);

    Result<PageResult<VideoListVO>> getVideoList(VideoPageQuery pageQuery);

    Result<VideoDetailVO> getVideoDetail(Long videoId);

    Result<PageResult<VideoListVO>> getVideoListByAuthorId(Long authorId, VideoPageQuery pageQuery);

    Result<PageResult<VideoListVO>> getMyVideoList(VideoPageQuery pageQuery);

    Result<String> generateVideoCover(VideoCoverMessage videoCoverMessage);

    Result<PageResult<ShortsVO>> getShortsFeed(PageQuery pageQuery);

    Result<Void> updatePlaybackResume(RecordUserWatchTimeDTO userWatchTimeDTO);

    Result<PageResult<VideoListVO>> getOtherUserVideoList(Long userId, PageQuery pageQuery);

    Result<PageResult<FollowVideoVO>> getFollowVideoList(PageQuery pageQuery);

    Result<Void> deleteVideo(Long videoId);

    Result<Void> privateVideo(Long videoId);

    Result<String> publicVideo(Long videoId);

    Result<Void> openComment(Long videoId);

    Result<Void> closeComment(Long videoId);

}
