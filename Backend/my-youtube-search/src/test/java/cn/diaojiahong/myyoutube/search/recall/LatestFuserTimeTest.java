package cn.diaojiahong.myyoutube.search.recall;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LatestFuserTimeTest {
    @Test void mixedTimestampFormatsUseTheSameInstantForLatestOrdering() throws Exception {
        var client = mock(ElasticsearchClient.class);
        List<Hit<Map>> hits = List.of(
                Hit.of(h -> h.index("content").id("1").source(Map.of("createTime", "2026-10-25 10:00:00"))),
                Hit.of(h -> h.index("content").id("2").source(Map.of("createTime", Instant.parse("2026-10-25T02:00:01Z").toEpochMilli()))),
                Hit.of(h -> h.index("content").id("3").source(Map.of("createTime", "2026-10-25T09:59:59+08:00"))));
        SearchResponse<Map> response = SearchResponse.of(s -> s.took(1).timedOut(false)
                .shards(sh -> sh.total(1).successful(1).failed(0)).hits(h -> h.hits(hits)));
        when(client.search(any(Function.class), eq(Map.class))).thenReturn(response);
        assertEquals(List.of("2", "1", "3"), new LatestFuser(client).fuse(null, List.of(List.of("1", "2", "3"))));
    }
}
