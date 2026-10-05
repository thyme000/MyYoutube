package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.UserDocSyncMessage;
import cn.diaojiahong.myyoutube.search.domain.doc.UserDoc;
import cn.diaojiahong.myyoutube.search.domain.doc.UserDocRepository;
import cn.diaojiahong.myyoutube.recommend.domain.doc.ContentDoc;
import cn.diaojiahong.myyoutube.recommend.domain.doc.ContentDocRepository;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.elasticsearch.core.suggest.Completion;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.TOPIC_ES_DOCUMENT,
        consumerGroup = "es_document_user_group",
        selectorExpression = MqConstants.TAG_ES_USER,
        maxReconsumeTimes = 3)
public class EsDocumentListener implements RocketMQListener<UserDocSyncMessage> {

    private final UserPort userPort;
    private final UserDocRepository userDocRepository;
    private final ContentDocRepository videoDocRepository;

    /**
     * 用户文档同步: 以 MySQL 为准写入 ES, 延迟投递天然合并短时间内的多次变更
     */
    @Override
    public void onMessage(UserDocSyncMessage message) {
        UserSummary user = userPort.getUser(message.getUserId());
        if (user == null) {
            log.warn("同步用户文档失败, 用户不存在: {}", message.getUserId());
            return;
        }
        // 同步刷新该用户的视频文档作者昵称
        try {
            List<ContentDoc> videoDocs = videoDocRepository.findContentDocByAuthorId(message.getUserId());
            if (videoDocs != null && !videoDocs.isEmpty()) {
                videoDocs.forEach(v -> {
                    v.setAuthorName(user.getNickname());
                    v.setSuggest(new Completion(List.of(
                            v.getTitle(),
                            v.getDescription(),
                            user.getNickname(),
                            String.join(" ", v.getTagNames())
                    )));
                });
                videoDocRepository.saveAll(videoDocs);
            }
        } catch (Exception e) {
            log.error("更新视频文档的作者昵称失败, userId={}", message.getUserId(), e);
        }

        UserDoc doc = userDocRepository.findById(message.getUserId()).orElse(null);
        if (doc == null) {
            doc = new UserDoc();
            doc.setId(user.getId());
            doc.setUsername(user.getUsername());
            doc.setNickname(user.getNickname());
            doc.setIntroduction(user.getIntroduction());
            doc.setLikeCount(user.getLikeCount());
            doc.setVideoCount(user.getVideoCount());
            doc.setCreateTime(user.getCreateTime());
        }
        doc.setIdKeyword(user.getId().toString());
        doc.setNickname(user.getNickname());
        doc.setIntroduction(user.getIntroduction());
        doc.setLikeCount(user.getLikeCount());
        doc.setVideoCount(user.getVideoCount());
        doc.setFans(user.getFans());
        doc.setSuggest(new Completion(List.of(
                user.getNickname()
        )));
        userDocRepository.save(doc);
    }
}
