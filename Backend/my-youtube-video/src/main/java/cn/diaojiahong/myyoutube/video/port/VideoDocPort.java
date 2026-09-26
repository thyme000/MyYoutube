package cn.diaojiahong.myyoutube.video.port;

import cn.diaojiahong.myyoutube.video.domain.po.Video;

/**
 * 视频 ES 文档端口: 由 search 模块提供实现, video 模块不直接依赖 ES
 */
public interface VideoDocPort {

    void deleteDoc(Long videoId);

    /**
     * 同步视频可见性到 ES 文档
     *
     * @param video          仅重建补全词时需要, 其余场景可传 null
     * @param rebuildSuggest public 且为 true 时按视频信息重建搜索补全词
     */
    void syncVisibility(Long videoId, Video video, String visibility, boolean rebuildSuggest);
}
