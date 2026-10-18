package cn.diaojiahong.myyoutube.search.vector;

import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 内容向量: MQ 消费构建 / 查询向量 / kNN 召回 / 手动重建全部收口;
 * 版本戳由 llm-svc 单点盖发, 本服务只透传, 保证文档向量与查询向量同一向量空间
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentVectorService {

    private static final String CONTENT_INDEX = "content";
    private static final String VECTOR_FIELD = "vector";
    private static final String REBUILD_LAST_ID_KEY = "search:vector:rebuild:last_id";
    private static final int REBUILD_BATCH = 50;

    private final LlmPort llmPort;
    private final ElasticsearchClient elasticsearchClient;
    private final StringRedisTemplate redisTemplate;
    private final VectorProperties vectorProperties;
    private final @Qualifier("vectorRebuildExecutor") ThreadPoolTaskExecutor vectorRebuildExecutor;

    private final AtomicBoolean rebuildRunning = new AtomicBoolean(false);

    public record QueryVector(List<Float> vector, String version) {
    }

    /** 查询向量: 失败返回 null, 调用方降级纯关键词 */
    public QueryVector embedQuery(String keyword) {
        if (!vectorProperties.isEnabled() || keyword == null || keyword.isBlank()) {
            return null;
        }
        try {
            LlmPort.EmbeddingsResponse resp = llmPort.embeddings(new LlmPort.EmbeddingsRequest(List.of(keyword)));
            return new QueryVector(resp.getVectors().get(0), resp.getVersion());
        } catch (Exception e) {
            log.warn("查询向量生成失败, 降级纯关键词搜索: {}", e.getMessage());
            return null;
        }
    }

    /** 写回成功才完成消费，异常交给 MQ 重试。 */
    public void embedContent(Long contentId) {
        var content = fetchContent(contentId);
        if (content == null || !"published".equals(content.get("status")) || !"public".equals(content.get("visibility"))) return;
        // 正文发布后不可编辑；模型切换沿用手动重建，重复投递复用已有结果。
        if (content.get("vectorVersion") instanceof String version && !version.isBlank()) return;
        String text = contentText(content);
        if (text.isEmpty()) return;
        var response = llmPort.embeddings(new LlmPort.EmbeddingsRequest(List.of(text)));
        updateVector(contentId, response.getVectors().get(0), response.getVersion());
        log.info("Content vector completed contentId={} version={}", contentId, response.getVersion());
    }

    /** kNN 语义召回: 只召回与查询向量同版本的已发布内容; 失败返回空, 调用方降级 */
    public List<String> knnSearch(QueryVector queryVector, String category, int size) {
        try {
            SearchResponse<Void> resp = elasticsearchClient.search(s -> s
                    .index(CONTENT_INDEX)
                    .size(size)
                    .knn(k -> k
                            .field(VECTOR_FIELD)
                            .queryVector(queryVector.vector())
                            .k(size)
                            .numCandidates(Math.max(size * 3, 100))
                            .filter(f -> f.bool(b -> {
                                b.must(m -> m.term(t -> t.field("status").value("published")));
                                b.must(m -> m.term(t -> t.field("visibility").value("public")));
                                if (category != null && !category.isBlank()) {
                                    b.must(m -> m.term(t -> t.field("category").value(category)));
                                }
                                b.must(m -> m.term(t -> t.field("vectorVersion").value(queryVector.version())));
                                return b;
                            }))
                    ), Void.class);
            return resp.hits().hits().stream().map(Hit::id).toList();
        } catch (Exception e) {
            log.warn("向量召回失败, 该路降级: {}", e.getMessage());
            return List.of();
        }
    }

    /** 存量重建: 扫描版本不等于当前配置的文档, 分批补算; 游标存 Redis, 挂了重跑自动续 */
    public boolean startRebuild() {
        if (!vectorProperties.isEnabled() || !rebuildRunning.compareAndSet(false, true)) {
            return false;
        }
        try {
            vectorRebuildExecutor.execute(this::doRebuild);
            return true;
        } catch (RejectedExecutionException rejected) {
            rebuildRunning.set(false);
            log.warn("Vector rebuild rejected, retry the request later");
            return false;
        }
    }

    public boolean isRebuildRunning() {
        return rebuildRunning.get();
    }

    public Long rebuildCursor() {
        String lastId = redisTemplate.opsForValue().get(REBUILD_LAST_ID_KEY);
        return lastId == null ? null : Long.valueOf(lastId);
    }

    private void doRebuild() {
        try {
            // 先探测当前向量空间版本, 后续只补算版本不一致的文档
            QueryVector probe = embedQuery("向量空间版本探测");
            if (probe == null) {
                log.error("向量重建中止: 无法从 llm-svc 获取向量版本");
                return;
            }
            String version = probe.version();
            log.info("向量重建开始, 目标版本 {}", version);
            long lastId = redisTemplate.opsForValue().get(REBUILD_LAST_ID_KEY) == null
                    ? 0 : Long.parseLong(redisTemplate.opsForValue().get(REBUILD_LAST_ID_KEY));
            long total = 0;
            while (true) {
                long cursor = lastId;
                SearchResponse<Void> resp = elasticsearchClient.search(s -> {
                    s.index(CONTENT_INDEX)
                            .size(REBUILD_BATCH)
                            .query(q -> q.bool(b -> b
                                    .filter(f -> f.term(t -> t.field("status").value("published")))
                                    .filter(f -> f.term(t -> t.field("visibility").value("public")))
                                    .mustNot(m -> m.term(t -> t.field("vectorVersion").value(version)))))
                            .sort(so -> so.field(f -> f.field("id").order(co.elastic.clients.elasticsearch._types.SortOrder.Asc)));
                    if (cursor > 0) {
                        s.searchAfter(List.of(FieldValue.of(cursor)));
                    }
                    return s;
                }, Void.class);
                List<Hit<Void>> hits = resp.hits().hits();
                if (hits.isEmpty()) {
                    break;
                }
                embedBatch(hits.stream().map(h -> Long.valueOf(h.id())).toList(), version);
                lastId = Long.valueOf(hits.get(hits.size() - 1).id());
                total += hits.size();
                redisTemplate.opsForValue().set(REBUILD_LAST_ID_KEY, String.valueOf(lastId));
            }
            redisTemplate.delete(REBUILD_LAST_ID_KEY);
            log.info("向量重建完成, 共补算 {} 篇, 版本 {}", total, version);
        } catch (Exception e) {
            log.error("向量重建中断, 游标已保存, 重新触发即可续跑", e);
        } finally {
            rebuildRunning.set(false);
        }
    }

    /** 批量补算: 批内任一失败整批重试, 部分成功也全量覆盖写(幂等) */
    private void embedBatch(List<Long> ids, String version) {
        List<String> texts = new ArrayList<>(ids.size());
        for (Long id : ids) {
            texts.add(fetchText(id));
        }
        IllegalStateException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                LlmPort.EmbeddingsResponse resp = llmPort.embeddings(new LlmPort.EmbeddingsRequest(texts));
                for (int i = 0; i < ids.size(); i++) {
                    updateVector(ids.get(i), resp.getVectors().get(i), version);
                }
                return;
            } catch (Exception e) {
                last = e instanceof IllegalStateException ise ? ise : new IllegalStateException(e.getMessage(), e);
                log.warn("向量重建批量补算失败(第 {} 次): {}", attempt, e.getMessage());
            }
        }
        throw last;
    }

    private String fetchText(Long id) {
        var content = fetchContent(id);
        if (content == null) throw new IllegalStateException("重建文档不存在, contentId=" + id);
        return contentText(content);
    }

    private Map<String, Object> fetchContent(Long id) {
        try {
            var response = elasticsearchClient.get(g -> g.index(CONTENT_INDEX).id(id.toString())
                    .sourceIncludes(List.of("title", "description", "summary", "status", "visibility", "vectorVersion")), Map.class);
            return response.found() ? response.source() : null;
        } catch (java.io.IOException error) {
            throw new IllegalStateException("向量文档读取失败, contentId=" + id, error);
        }
    }

    private void updateVector(Long contentId, List<Float> vector, String version) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put(VECTOR_FIELD, vector);
        doc.put("vectorVersion", version);
        try {
            elasticsearchClient.update(u -> u
                    .index(CONTENT_INDEX)
                    .id(String.valueOf(contentId))
                    .doc(JsonData.of(doc)), Void.class);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("向量写回失败: " + e.getMessage(), e);
        }
    }

    private String contentText(Map<String, Object> content) {
        return Stream.of("title", "description", "summary").map(content::get).filter(Objects::nonNull)
                .map(Object::toString).map(String::trim).filter(text -> !text.isEmpty()).collect(Collectors.joining("\n"));
    }
}
