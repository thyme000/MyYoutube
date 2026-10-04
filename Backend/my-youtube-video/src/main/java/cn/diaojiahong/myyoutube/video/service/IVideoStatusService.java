package cn.diaojiahong.myyoutube.video.service;

/**
 * 视频状态变更: 删除/私密/公开/评论开关
 */
public interface IVideoStatusService {

    void deleteVideo(Long videoId);

    void privateVideo(Long videoId);

    String publicVideo(Long videoId);

    void openComment(Long videoId);

    void closeComment(Long videoId);
}
