package cn.diaojiahong.myyoutube.recommend.service;

import cn.diaojiahong.myyoutube.recommend.domain.po.VideoTag;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 视频-标签关联表 服务类
 * </p>
 *
 * @author thyme
 * @since 2026-03-25
 */
public interface IVideoTagService extends IService<VideoTag> {

    /**
     * 保存某个视频的标签关联关系（先清理旧关联，再写入新关联）。
     *
     * @param tags    前端传入的标签字符串（逗号分隔）
     * @param videoId 视频 ID
     */
    void saveTagsAsync(String tags, Long videoId, Video video);

}
