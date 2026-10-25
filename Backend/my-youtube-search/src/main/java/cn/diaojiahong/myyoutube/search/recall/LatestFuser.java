package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.common.utils.BusinessTime;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 最新排序融合: 与名次无关, 取各路并集后按 createTime 重排
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LatestFuser implements RecallFuser {

    private static final String CONTENT_INDEX = "content";
    private static final DateTimeFormatter TEXT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ElasticsearchClient elasticsearchClient;

    @Override
    public List<String> fuse(SearchContext ctx, List<List<String>> recalls) {
        List<String> ids = recalls.stream().flatMap(List::stream).distinct().toList();
        if (ids.isEmpty()) {
            return ids;
        }
        Map<String, Long> times = fetchCreateTime(ids);
        return ids.stream()
                .sorted(Comparator
                        .comparingLong((String id) -> times.getOrDefault(id, 0L)).reversed()
                        .thenComparing(Comparator.<String>comparingLong(Long::parseLong).reversed()))
                .toList();
    }

    /** 并集 id 回填 createTime: 一次 ids 查询, 失败退化为按 id 序, 不阻塞搜索 */
    private Map<String, Long> fetchCreateTime(List<String> ids) {
        try {
            SearchResponse<Map> resp = elasticsearchClient.search(s -> s
                    .index(CONTENT_INDEX)
                    .size(ids.size())
                    .query(q -> q.ids(v -> v.values(ids)))
                    .source(sc -> sc.filter(f -> f.includes("createTime"))), Map.class);
            Map<String, Long> times = new HashMap<>();
            for (Hit<Map> hit : resp.hits().hits()) {
                Long time = parseTime(hit.source() == null ? null : hit.source().get("createTime"));
                if (time != null) {
                    times.put(hit.id(), time);
                }
            }
            return times;
        } catch (Exception e) {
            log.warn("createTime 回填失败, 最新排序退化为 id 序: {}", e.getMessage());
            return Map.of();
        }
    }

    /** 映射是 yyyy-MM-dd HH:mm:ss || epoch_millis 双格式, 两种形态都要吃下 */
    private Long parseTime(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            return n.longValue();
        }
        String text = raw.toString().trim();
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
        }
        try {
            return BusinessTime.toInstant(LocalDateTime.parse(text, TEXT_TIME)).toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return OffsetDateTime.parse(text).toInstant().toEpochMilli();
        } catch (Exception ignored) {
        }
        return null;
    }
}
