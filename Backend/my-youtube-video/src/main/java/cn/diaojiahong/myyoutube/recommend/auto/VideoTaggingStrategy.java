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
    public TagSignal loadSignal(Long contentId) {
        Video video = videoMapper.selectById(contentId);
        if (video == null || (video.getIsDelete() != null && video.getIsDelete() == 1)) {
            return null;
        }
        String title = video.getTitle() == null ? "" : video.getTitle().trim();
        String description = video.getDescription() == null ? "" : video.getDescription().trim();
        if (title.isEmpty() && description.isEmpty()) {
            return null;
        }
        return new TagSignal(title, description);
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
