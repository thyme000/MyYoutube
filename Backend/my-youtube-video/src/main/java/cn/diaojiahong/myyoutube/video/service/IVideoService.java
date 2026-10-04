package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
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

    String publishVideo(VideoDTO videoDTO);
    /**
     * 事务消息的本地事务: 视频入库 + 标签异步保存
     */
    void persistNewVideo(Video video);

    PageResult<VideoListVO> getVideoList(VideoPageQuery pageQuery);
    VideoDetailVO getVideoDetail(Long videoId);
    PageResult<VideoListVO> getVideoListByAuthorId(Long authorId, VideoPageQuery pageQuery);
    PageResult<VideoListVO> getMyVideoList(VideoPageQuery pageQuery);
    String generateVideoCover(VideoCoverMessage videoCoverMessage);
    PageResult<ShortsVO> getShortsFeed(PageQuery pageQuery);
    void updatePlaybackResume(RecordUserWatchTimeDTO userWatchTimeDTO);
    PageResult<VideoListVO> getOtherUserVideoList(Long userId, PageQuery pageQuery);
    PageResult<FollowVideoVO> getFollowVideoList(PageQuery pageQuery);
    void deleteVideo(Long videoId);
    void privateVideo(Long videoId);
    String publicVideo(Long videoId);
    void openComment(Long videoId);
    void closeComment(Long videoId);
}
