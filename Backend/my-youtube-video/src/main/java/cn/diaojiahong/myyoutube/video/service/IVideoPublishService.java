package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.video.domain.dto.DraftPublishDTO;
import cn.diaojiahong.myyoutube.video.domain.dto.GenerateCoverDTO;
import cn.diaojiahong.myyoutube.video.domain.dto.VideoDTO;
import cn.diaojiahong.myyoutube.video.domain.po.Video;

/**
 * 视频发布: 发布入口 + 事务消息本地事务 + 封面任务触发 + 草稿发布
 */
public interface IVideoPublishService {

    String publishVideo(VideoDTO videoDTO);

    /**
     * 事务消息的本地事务: 视频入库 + 标签异步保存
     */
    void persistNewVideo(Video video);

    String generateVideoCover(GenerateCoverDTO generateCoverDTO);

    /**
     * 草稿发布: 管线处理完成的草稿转正为视频, 秒级操作
     */
    String publishDraft(DraftPublishDTO dto);

    /**
     * 草稿处理状态查询(属主校验), 上传页轮询
     */
    cn.diaojiahong.myyoutube.video.domain.vo.DraftStatusVO getDraftStatus(Long draftId);
}
