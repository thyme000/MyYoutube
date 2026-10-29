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
import java.util.Map;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.data.elasticsearch.core.query.ScriptType;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.TOPIC_ES_DOCUMENT,
        consumerGroup = "es_document_user_group",
        selectorExpression = MqConstants.TAG_ES_USER,
        maxReconsumeTimes = 3)
public class EsDocumentListener implements RocketMQListener<UserDocSyncMessage> {

    private static final String AUTHOR_SCRIPT = """
            if (ctx._source.deleted == true) { ctx.op = 'noop'; }
            else {
                ctx._source.authorName = params.name;
                def words = [];
                if (ctx._source.status == 'published' && ctx._source.visibility == 'public') {
                    if (ctx._source.title != null) { words.add(ctx._source.title); }
                    if (ctx._source.description != null) { words.add(ctx._source.description); }
                    words.add(params.name);
                    if (ctx._source.tagNames != null) { words.addAll(ctx._source.tagNames); }
                }
                ctx._source.suggest = ['input': words];
            }
            """;

    private final UserPort userPort;
    private final UserDocRepository userDocRepository;
    private final ContentDocRepository contentDocRepository;
    private final ElasticsearchOperations operations;

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
        // 局部更新，避免从旧副本整文档写回而覆盖并发可见性／审核状态。
        try {
            List<ContentDoc> contents = contentDocRepository.findContentDocByAuthorId(message.getUserId());
            if (contents != null && !contents.isEmpty()) {
                var updates = contents.stream().map(content -> {
                    return UpdateQuery.builder(content.getId().toString())
                            .withScriptType(ScriptType.INLINE).withLang("painless").withScript(AUTHOR_SCRIPT)
                            .withParams(Map.of("name", user.getNickname() == null ? "" : user.getNickname()))
                            .withRetryOnConflict(3).build();
                }).toList();
                operations.bulkUpdate(updates, IndexCoordinates.of("content"));
            }
        } catch (Exception e) {
            log.error("更新内容文档的作者昵称失败, userId={}", message.getUserId(), e);
        }

        UserDoc doc = userDocRepository.findById(message.getUserId()).orElse(null);
        if (doc == null) {
            doc = new UserDoc();
            doc.setId(user.getId());
            doc.setUsername(user.getUsername());
            doc.setNickname(user.getNickname());
            doc.setIntroduction(user.getIntroduction());
            doc.setLikeCount(user.getLikeCount());
            doc.setCreateTime(user.getCreateTime());
        }
        doc.setIdKeyword(user.getId().toString());
        doc.setNickname(user.getNickname());
        doc.setIntroduction(user.getIntroduction());
        doc.setLikeCount(user.getLikeCount());
        doc.setFans(user.getFans());
        doc.setSuggest(user.getNickname() == null ? new Completion() : new Completion(List.of(user.getNickname())));
        userDocRepository.save(doc);
    }
}
