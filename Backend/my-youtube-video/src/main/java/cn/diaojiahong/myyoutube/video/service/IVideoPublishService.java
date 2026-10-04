package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.common.mq.message.VideoCoverMessage;
import cn.diaojiahong.myyoutube.video.domain.dto.VideoDTO;
import cn.diaojiahong.myyoutube.video.domain.po.Video;

/**
 * 视频发布: 发布入口 + 事务消息本地事务 + 封面任务触发
 */
public interface IVideoPublishService {

    String publishVideo(VideoDTO videoDTO);

    /**
     * 事务消息的本地事务: 视频入库 + 标签异步保存
     */
    void persistNewVideo(Video video);

    String generateVideoCover(VideoCoverMessage videoCoverMessage);
}
