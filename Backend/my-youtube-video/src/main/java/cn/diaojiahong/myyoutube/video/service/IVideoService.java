package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.video.domain.po.Video;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 视频信息表 MyBatis-Plus CRUD 门面;
 * 业务能力已按职责拆分: IVideoPublishService / IVideoQueryService / IVideoStatusService / IPlaybackService
 */
public interface IVideoService extends IService<Video> {
}
