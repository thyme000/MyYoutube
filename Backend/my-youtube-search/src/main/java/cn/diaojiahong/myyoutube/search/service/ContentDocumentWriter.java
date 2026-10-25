package cn.diaojiahong.myyoutube.search.service;

import cn.diaojiahong.myyoutube.common.mq.message.ContentDocMessage;
import cn.diaojiahong.myyoutube.recommend.domain.doc.ContentDoc;
import cn.diaojiahong.myyoutube.search.vector.ContentVectorPublisher;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.data.elasticsearch.core.query.ScriptType;
import org.springframework.data.elasticsearch.core.query.UpdateResponse;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.suggest.Completion;
import org.springframework.stereotype.Service;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ContentDocumentWriter {
    private static final String WRITE_SCRIPT = """
            if (ctx._source.indexVersion != null && ctx._source.indexVersion > params.document.indexVersion) {
                ctx.op = 'noop';
            } else {
                if (params.document.deleted == true) { ctx._source.clear(); }
                ctx._source.putAll(params.document);
            }
            """;
    private final ElasticsearchOperations operations;
    private final UserPort userPort;
    private final ContentVectorPublisher vectorPublisher;

    public void write(ContentDocMessage message) {
        if (message.getContentId() == null || message.getIndexVersion() == null || message.getIndexVersion() < 1)
            throw new IllegalArgumentException("Content index identity and version are required");
        if (message.isDeleted()) {
            writeDocument(message, Document.from(Map.of("id", message.getContentId(), "indexVersion", message.getIndexVersion(),
                    "deleted", true, "status", "banned", "visibility", "private", "suggest", Map.of("input", List.of()))));
            return;
        }
        List<Long> tagIds = message.getTagIds();
        List<String> list = null;
        if (message.getTags() != null) {
            list = Arrays.stream(message.getTags().split(",")).toList();
        }

        ContentDoc doc = new ContentDoc();
        doc.setId(message.getContentId());
        doc.setIndexVersion(message.getIndexVersion());
        doc.setDeleted(false);
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
        boolean applied = writeDocument(message, operations.getElasticsearchConverter().mapObject(doc));
        if (applied && searchable) {
            vectorPublisher.publish(message.getContentId());
        }
    }

    private boolean writeDocument(ContentDocMessage message, Document document) {
        // 只合并内容字段，保留独立写入的向量；删除留下版本墓碑以拒绝迟到写入。
        var update = UpdateQuery.builder(message.getContentId().toString())
                .withScriptType(ScriptType.INLINE).withLang("painless").withScript(WRITE_SCRIPT)
                .withParams(Map.of("document", document)).withScriptedUpsert(true).withUpsert(Document.create())
                .withRetryOnConflict(3).withRefreshPolicy(RefreshPolicy.IMMEDIATE).build();
        return operations.update(update, IndexCoordinates.of("content")).getResult() != UpdateResponse.Result.NOOP;
    }
}
