package cn.diaojiahong.myyoutube.search.port;

import cn.diaojiahong.myyoutube.recommend.domain.doc.ContentDocRepository;
import cn.diaojiahong.myyoutube.recommend.domain.doc.ContentDoc;
import cn.diaojiahong.myyoutube.search.api.ContentDocPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import cn.diaojiahong.myyoutube.video.api.ContentInternalPort;
import cn.diaojiahong.myyoutube.search.service.ContentDocumentWriter;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Component
@RequiredArgsConstructor
public class ContentDocPortImpl implements ContentDocPort {

    private final ContentDocRepository contentDocRepository;
    private final ContentInternalPort contents;
    private final ContentDocumentWriter writer;
    private final ElasticsearchOperations operations;

    @Override
    public void rebuildContent(Long contentId) {
        var current = contents.searchDocument(contentId);
        if (current != null) writer.write(current);
    }

    @Override
    public void deleteDoc(Long contentId) {
        rebuildContent(contentId);
    }

    @Override
    public void syncPlayLikeBatch(List<CountSync> counts) {
        if (counts == null || counts.isEmpty()) {
            return;
        }
        List<Long> ids = counts.stream().map(CountSync::contentId).toList();
        var existing = StreamSupport
                .stream(contentDocRepository.findAllById(ids).spliterator(), false)
                .map(ContentDoc::getId).collect(Collectors.toSet());
        List<UpdateQuery> updated = new ArrayList<>();
        for (CountSync c : counts) {
            if (!existing.contains(c.contentId())) {
                continue;
            }
            updated.add(UpdateQuery.builder(c.contentId().toString())
                    .withDocument(Document.from(Map.of("playCount", c.playCount(), "likeCount", c.likeCount())))
                    .withDocAsUpsert(false).withRetryOnConflict(3).build());
        }
        if (!updated.isEmpty()) operations.bulkUpdate(updated, IndexCoordinates.of("content"));
    }

}
