package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.mq.message.VideoDocMessage;
import cn.diaojiahong.myyoutube.recommend.domain.doc.VideoDoc;
import cn.diaojiahong.myyoutube.recommend.domain.doc.VideoDocRepository;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.elasticsearch.core.suggest.Completion;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.TOPIC_ES_DOCUMENT,
        consumerGroup = "es_document_video_group",
        selectorExpression = MqConstants.TAG_ES_VIDEO,
        maxReconsumeTimes = 3)
public class VideoDocListener implements RocketMQListener<VideoDocMessage> {

    private final VideoDocRepository videoDocRepository;
    private final IUserService userService;

    @Override
    public void onMessage(VideoDocMessage message) {
        Video video = message.getVideo();
        List<Long> tagIds = message.getTagIds();
        List<String> list = null;
        if (video.getTags() != null) {
            list = Arrays.stream(video.getTags().split(",")).toList();
        }

        VideoDoc doc = new VideoDoc();
        doc.setId(video.getId());
        doc.setTitle(video.getTitle());
        doc.setCategory(video.getCategory().getCode());
        doc.setVideoType(video.getVideoType());
        doc.setStatus(video.getStatus().getCode());
        doc.setDescription(video.getDescription());
        doc.setTagIds(tagIds);
        doc.setAuthorId(video.getAuthorId());
        doc.setPlayCount(video.getPlayCount() != null ? video.getPlayCount() : 0L);
        doc.setLikeCount(video.getLikeCount() != null ? video.getLikeCount() : 0L);
        doc.setCreateTime(video.getCreateTime());
        doc.setVisibility(video.getVisibility().getCode());
        doc.setTagNames(list);
        doc.setAuthorName(userService.getById(video.getAuthorId() == null ? null : video.getAuthorId()).getNickname());
        List<String> suggest = new java.util.ArrayList<>(List.of(
                video.getTitle(),
                video.getDescription(),
                doc.getAuthorName() == null ? "" : doc.getAuthorName()
        ));
        if (list != null && !list.isEmpty()) {
            suggest.addAll(list);
        }
        doc.setSuggest(new Completion(suggest));
        videoDocRepository.save(doc);
    }
}
