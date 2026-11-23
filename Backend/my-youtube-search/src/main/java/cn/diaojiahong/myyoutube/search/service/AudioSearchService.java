package cn.diaojiahong.myyoutube.search.service;

import cn.diaojiahong.myyoutube.common.media.AudioDiscoveryContracts;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.common.media.AudioDiscoveryContracts.*;
import cn.diaojiahong.myyoutube.search.api.AudioSearchPort;
import cn.diaojiahong.myyoutube.search.domain.doc.AudioDoc;
import cn.diaojiahong.myyoutube.content.api.AudioDocumentPort;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.*;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import lombok.RequiredArgsConstructor;
import org.springframework.data.elasticsearch.core.IndexOperations;
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
        AudioDiscoveryContracts.Document document = source.document(id);
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
        } catch (IOException error) {
            throw new IllegalStateException("Audio index write failed", error);
        }
    }

    @Override
    public Slice search(AudioDiscoveryContracts.Query request) {
        request.validate();
        AudioSearchCursor.Position position = AudioSearchCursor.decode(request);
        try {
            ensureIndex();
            SearchResponse<AudioDoc> response = es.search(builder -> {
                SearchRequest.Builder search = builder.index(INDEX)
                        .size(CursorPage.fetchSize(request.pageSize()))
                        .query(query(request))
                        .sort(sort -> sort.field(f -> f.field(AudioDoc.Fields.createdAt).order(SortOrder.Desc)))
                        .sort(sort -> sort.field(f -> f.field(AudioDoc.Fields.id).order(SortOrder.Desc)));
                if (position != null) {
                    search.searchAfter(List.of(FieldValue.of(position.createdAt()), FieldValue.of(position.id())));
                }
                return search;
            }, AudioDoc.class);

            List<Hit<AudioDoc>> hits = response.hits().hits();
            CursorPage<Hit<AudioDoc>, String> page = CursorPage.slice(hits, request.pageSize(), hit -> cursor(request, hit));
            List<Long> ids = new ArrayList<>();
            for (Hit<AudioDoc> hit : page.items()) {
                ids.add(Objects.requireNonNull(hit.source()).getId());
            }
            return new Slice(ids, page.nextCursor());
        } catch (IOException error) {
            throw new IllegalStateException("Audio search failed", error);
        }
    }

    private static String cursor(AudioDiscoveryContracts.Query request, Hit<AudioDoc> hit) {
        AudioDoc last = Objects.requireNonNull(hit.source());
        return AudioSearchCursor.encode(request, last.getCreatedAt(), last.getId());
    }

    static Query query(AudioDiscoveryContracts.Query request) {
        String keyword = AudioSearchCursor.keywordOf(request.keyword());
        return Query.of(q -> q.bool(b -> {
            b.filter(f -> f.term(t -> t.field(AudioDoc.Fields.deleted).value(false)));
            if (request.uploaderId() != null) b.filter(f -> f.term(t -> t.field(AudioDoc.Fields.uploaderId).value(request.uploaderId())));
            else {
                b.filter(f -> f.term(t -> t.field(AudioDoc.Fields.visibility).value("public")));
                b.filter(f -> f.term(t -> t.field(AudioDoc.Fields.processingStatus).value("ready")));
                b.filter(f -> f.term(t -> t.field(AudioDoc.Fields.reviewStatus).value("approved")));
            }
            if (keyword != null)
                b.must(f -> f.match(t -> t.field(AudioDoc.Fields.title).query(keyword).operator(Operator.And)));
            return b;
        }));
    }

    private void ensureIndex() {
        if (initialized) return;
        IndexOperations index = operations.indexOps(AudioDoc.class);
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
