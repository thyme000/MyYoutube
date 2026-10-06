package cn.diaojiahong.myyoutube.recommend.auto;

import cn.diaojiahong.myyoutube.common.mq.message.ContentDocMessage;
import cn.diaojiahong.myyoutube.content.support.ContentDocMessages;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.api.enums.VideoCategoryEnum;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.mapper.VideoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class VideoTaggingStrategy implements ContentTaggingStrategy {

    private final VideoMapper videoMapper;

    @Override
    public ContentTypeEnum type() {
        return ContentTypeEnum.VIDEO;
    }

    @Override
    public UserInput buildUserInput(Long contentId) {
        Video video = videoMapper.selectById(contentId);
        if (video == null || (video.getIsDelete() != null && video.getIsDelete() == 1)) {
            return null;
        }
        String title = video.getTitle() == null ? "" : video.getTitle().trim();
        String description = video.getDescription() == null ? "" : video.getDescription().trim();
        if (title.isEmpty() && description.isEmpty()) {
            return null;
        }
        // v2: 此处补视频截帧(FFmpeg 抽关键帧转 base64), 与文本一起走多模态
        return UserInput.ofText("【标题】" + title + "\n【简介】" + description);
    }

    @Override
    public void persistResult(Long contentId, String categoryCode, String tagsCache) {
        videoMapper.updateById(new Video()
                .setId(contentId)
                .setCategory(VideoCategoryEnum.fromCode(categoryCode))
                .setTags(tagsCache));
    }

    @Override
    public ContentDocMessage buildEsMessage(Long contentId, List<Long> tagIds) {
        // 重查取最新 status/visibility/tags 缓存, 缩小与审核/可见性变更的窗口
        Video video = videoMapper.selectById(contentId);
        if (video == null || (video.getIsDelete() != null && video.getIsDelete() == 1)) {
            return null;
        }
        return ContentDocMessages.of(video, tagIds);
    }
}
