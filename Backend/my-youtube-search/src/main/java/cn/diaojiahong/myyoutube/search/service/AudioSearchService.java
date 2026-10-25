package cn.diaojiahong.myyoutube.search.service;

import cn.diaojiahong.myyoutube.common.media.AudioDiscoveryContracts;
import cn.diaojiahong.myyoutube.common.media.AudioDiscoveryContracts.*;
import cn.diaojiahong.myyoutube.search.api.AudioSearchPort;
import cn.diaojiahong.myyoutube.search.domain.doc.AudioDoc;
import cn.diaojiahong.myyoutube.video.api.AudioDocumentPort;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.*;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.elasticsearch.client.ResponseException;
import java.io.IOException;
import java.util.*;
import static cn.diaojiahong.myyoutube.search.domain.doc.AudioDoc.INDEX;

@Service
@RequiredArgsConstructor
public class AudioSearchService implements AudioSearchPort {
    private final ElasticsearchClient es;
    private final AudioDocumentPort source;
    private final ElasticsearchOperations operations;
    private volatile boolean initialized;

    @Override
    public void rebuild(Long id) {
        var document = source.document(id);
        if (document == null) return;
        try {
            ensureIndex();
            try {
                es.index(i -> i.index(INDEX).id(id.toString()).version(document.version()).versionType(VersionType.ExternalGte)
                        .document(AudioDoc.from(document)).refresh(Refresh.True));
            } catch (ElasticsearchException conflict) {
                if (conflict.status() != 409) throw conflict;
            } catch (ResponseException conflict) {
                if (conflict.getResponse().getStatusLine().getStatusCode() != 409) throw conflict;
            }
            source.acknowledge(new Ack(id, document.version()));
        } catch (IOException error) { throw new IllegalStateException("Audio index write failed", error); }
    }

    @Override
    public Slice search(AudioDiscoveryContracts.Query request) {
        request.validate();
        try {
            ensureIndex();
            var response = es.search(s -> s.index(INDEX).from((request.pageNo() - 1) * request.pageSize()).size(request.pageSize() + 1)
                    .query(query(request)).sort(sort -> sort.field(f -> f.field(AudioDoc.Fields.createdAt).order(SortOrder.Desc)))
                    .sort(sort -> sort.field(f -> f.field(AudioDoc.Fields.id).order(SortOrder.Desc))), AudioDoc.class);
            var hits = response.hits().hits();
            return new Slice(hits.stream().limit(request.pageSize()).map(h -> Objects.requireNonNull(h.source()).getId()).toList(), hits.size() > request.pageSize());
        } catch (IOException error) { throw new IllegalStateException("Audio search failed", error); }
    }

    static Query query(AudioDiscoveryContracts.Query request) {
        return Query.of(q -> q.bool(b -> {
            b.filter(f -> f.term(t -> t.field(AudioDoc.Fields.deleted).value(false)));
            if (request.uploaderId() != null) b.filter(f -> f.term(t -> t.field(AudioDoc.Fields.uploaderId).value(request.uploaderId())));
            else {
                b.filter(f -> f.term(t -> t.field(AudioDoc.Fields.visibility).value("public")));
                b.filter(f -> f.term(t -> t.field(AudioDoc.Fields.processingStatus).value("ready")));
                b.filter(f -> f.term(t -> t.field(AudioDoc.Fields.reviewStatus).value("approved")));
            }
            if (request.keyword() != null && !request.keyword().isBlank())
                b.must(f -> f.match(t -> t.field(AudioDoc.Fields.title).query(request.keyword().trim()).operator(Operator.And)));
            return b;
        }));
    }

    private void ensureIndex() {
        if (initialized) return;
        var index = operations.indexOps(AudioDoc.class);
        if (!index.exists()) {
            try {
                if (!index.createWithMapping()) throw new IllegalStateException("Audio index creation not acknowledged");
            } catch (DataAccessException raced) {
                if (!index.exists()) throw raced;
            }
        }
        initialized = true;
    }
}
