package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.ContentDocMessage;
import cn.diaojiahong.myyoutube.recommend.domain.doc.ContentDoc;
import cn.diaojiahong.myyoutube.recommend.domain.doc.ContentDocRepository;
import cn.diaojiahong.myyoutube.user.api.UserPort;
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
        consumerGroup = "es_document_content_group",
        selectorExpression = MqConstants.TAG_ES_CONTENT,
        maxReconsumeTimes = 3)
public class ContentDocListener implements RocketMQListener<ContentDocMessage> {

    private final ContentDocRepository contentDocRepository;
    private final UserPort userPort;

    @Override
    public void onMessage(ContentDocMessage message) {
        List<Long> tagIds = message.getTagIds();
        List<String> list = null;
        if (message.getTags() != null) {
            list = Arrays.stream(message.getTags().split(",")).toList();
        }

        ContentDoc doc = new ContentDoc();
        doc.setId(message.getContentId());
        doc.setType(message.getType());
        doc.setCoverUrl(message.getCoverUrl());
        doc.setTitle(message.getTitle());
        doc.setCategory(message.getCategoryCode());
        doc.setStatus(message.getStatusCode());
        doc.setDescription(message.getDescription());
        doc.setTagIds(tagIds);
        doc.setAuthorId(message.getAuthorId());
        doc.setPlayCount(message.getPlayCount() != null ? message.getPlayCount() : 0L);
        doc.setLikeCount(message.getLikeCount() != null ? message.getLikeCount() : 0L);
        doc.setCreateTime(message.getCreateTime());
        doc.setVisibility(message.getVisibilityCode());
        doc.setTagNames(list);
        var author = userPort.getUser(message.getAuthorId());
        doc.setAuthorName(author != null && author.getNickname() != null ? author.getNickname() : "");
        List<String> suggest = new java.util.ArrayList<>(List.of(
                message.getTitle(),
                message.getDescription() == null ? "" : message.getDescription(),
                doc.getAuthorName() == null ? "" : doc.getAuthorName()
        ));
        if (list != null && !list.isEmpty()) {
            suggest.addAll(list);
        }
        doc.setSuggest(new Completion(suggest));
        contentDocRepository.save(doc);
    }
}
