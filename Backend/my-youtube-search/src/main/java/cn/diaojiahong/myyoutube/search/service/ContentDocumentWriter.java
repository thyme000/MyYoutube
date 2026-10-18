package cn.diaojiahong.myyoutube.search.service;

import cn.diaojiahong.myyoutube.common.mq.message.ContentDocMessage;
import cn.diaojiahong.myyoutube.recommend.domain.doc.ContentDoc;
import cn.diaojiahong.myyoutube.search.vector.ContentVectorService;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.data.elasticsearch.core.suggest.Completion;
import org.springframework.stereotype.Service;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ContentDocumentWriter {
    private final ElasticsearchOperations operations;
    private final UserPort userPort;
    private final ContentVectorService contentVectorService;

    public void write(ContentDocMessage message) {
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
        doc.setSummary(message.getSummary());
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
        boolean searchable = "published".equals(message.getStatusCode()) && "public".equals(message.getVisibilityCode());
        doc.setSuggest(searchable
                ? new Completion(suggest) : new Completion());
        // 部分更新不携带向量字段，保留异步写回的向量。
        var update = UpdateQuery.builder(message.getContentId().toString())
                .withDocument(operations.getElasticsearchConverter().mapObject(doc))
                .withDocAsUpsert(true).withRetryOnConflict(3).withRefreshPolicy(RefreshPolicy.IMMEDIATE).build();
        operations.update(update, IndexCoordinates.of("content"));
        if (searchable) {
            contentVectorService.embedAsync(message.getContentId(), message.getTitle(), message.getDescription(), message.getSummary());
        }
    }
}
