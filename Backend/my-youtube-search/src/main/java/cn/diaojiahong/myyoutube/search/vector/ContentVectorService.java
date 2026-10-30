package cn.diaojiahong.myyoutube.search.vector;

import cn.diaojiahong.myyoutube.common.idempotent.DuplicatePolicy;
import cn.diaojiahong.myyoutube.common.idempotent.Idempotent;
import cn.diaojiahong.myyoutube.common.idempotent.IdempotentGuard;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContentVectorService {
    private static final String CONTENT_INDEX = "content";
    private static final String VECTOR_FIELD = "vector";
    private static final String REBUILD_KEY = "{content-vector}:rebuild";
    private static final String CHECKPOINT_KEY = "search:{content-vector}:rebuild";
    private static final int REBUILD_BATCH = 50;

    private static final RedisScript<Long> SAVE_CHECKPOINT = RedisScript.of(
            new ClassPathResource("lua/vector-rebuild-checkpoint.lua"), Long.class);
    private final LlmPort llmPort;
    private final ElasticsearchClient elasticsearchClient;
    private final StringRedisTemplate redisTemplate;
    private final VectorProperties vectorProperties;

    private final @Qualifier("vectorRebuildExecutor") ThreadPoolTaskExecutor vectorRebuildExecutor;
    private final IdempotentGuard idempotentGuard;
    private final ContentVectorBuilder builder;

    private final AtomicBoolean rebuildRunning = new AtomicBoolean(false);

    public record QueryVector(List<Float> vector, String version) {
    }

    public record RebuildReport(String targetVersion, long cursor, ContentVectorBuilder.Counts counts,
                                boolean scanCompleted, String error) {
    }

    /** 后台重建探测向量空间，沿用内容构建的 RPC 规则。 */
    public QueryVector embedQuery(String keyword) {
        if (!vectorProperties.isEnabled() || keyword == null || keyword.isBlank()) {
            return null;
        }

        try {
            LlmPort.EmbeddingsResponse resp = llmPort.embeddings(
                    new LlmPort.EmbeddingsRequest(List.of(keyword)),
                    vectorProperties.embeddingOptions(1));
            LlmPort.EmbeddingItem item = resp.getItems().get(0);
            if (resp.getUnavailableReason() != null || item.index() != 0 || item.error() != null
                    || item.vector() == null || item.vector().isEmpty()) return null;
            return new QueryVector(item.vector(), resp.getVersion());
        } catch (Exception e) {
            log.warn("查询向量生成失败, 降级纯关键词搜索: {}", e.getMessage());
            return null;
        }
    }

    /** 写回成功才完成消费，异常交给 MQ 重试。 */
    @Idempotent(key = "'{content-vector}:' + #contentId", ttl = 0, policy = DuplicatePolicy.SKIP)
    public void embedContent(Long contentId) {
        builder.single(contentId, null, idempotentGuard.currentLease());
    }

    @Idempotent(key = "'{content-vector}:' + #contentId", ttl = 0, policy = DuplicatePolicy.SKIP)
    public void embedContent(Long contentId, String targetVersion) {
        builder.single(contentId, targetVersion, idempotentGuard.currentLease());
    }

    /** kNN 语义召回: 只召回与查询向量同版本的已发布内容; 失败返回空, 调用方降级 */
    public List<String> knnSearch(QueryVector queryVector, String category, int size) {
        try {
            SearchResponse<Void> resp = elasticsearchClient.search(knnRequest(queryVector, category, size), Void.class);
            return resp.hits().hits().stream().map(Hit::id).toList();
        } catch (Exception e) {
            log.warn("向量召回失败, 该路降级: {}", e.getMessage());
            return List.of();
        }
    }

    static SearchRequest knnRequest(QueryVector queryVector, String category, int size) {
        return SearchRequest.of(s -> s.index(CONTENT_INDEX)
                    .size(size)
                    .knn(k -> k.field(VECTOR_FIELD)
                            .queryVector(queryVector.vector())
                            .k(size)
                            .numCandidates(Math.max(size * 3, 100))
                            .filter(knnFilter(queryVector, category))
                    ));
    }

    static Query knnFilter(QueryVector queryVector, String category) {
        return Query.of(f -> f.bool(b -> {
            b.must(m -> m.term(t -> t.field("status").value("published")));
            b.must(m -> m.term(t -> t.field("visibility").value("public")));
            if (category != null && !category.isBlank()) {
                b.must(m -> m.term(t -> t.field("category").value(category)));
            }
            b.must(m -> m.term(t -> t.field("vectorVersion").value(queryVector.version())));
            return b;
        }));
    }

    public boolean startRebuild() {
        if (!vectorProperties.isEnabled() || !rebuildRunning.compareAndSet(false, true)) return false;
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
        RebuildReport report = rebuildReport();
        return report == null ? null : report.cursor();
    }

    public RebuildReport rebuildReport() {
        String saved = redisTemplate.opsForValue().get(CHECKPOINT_KEY);
        return saved == null ? null : JsonUtil.fromJson(saved, RebuildReport.class);
    }

    private void doRebuild() {
        try (IdempotentGuard.Lease lease = idempotentGuard.acquire(REBUILD_KEY, IdempotentGuard.DEFAULT_LEASE_SECONDS)) {
            if (!lease.acquired()) throw new IllegalStateException("向量重建占位状态异常");
            RebuildReport report = null;
            try {
                QueryVector probe = embedQuery("向量空间版本探测");
                if (probe == null || probe.version() == null || probe.version().isBlank()) {
                    throw new IllegalStateException("无法从 llm-svc 获取向量版本");
                }
                String version = probe.version();
                RebuildReport saved = rebuildReport();
                report = saved != null && version.equals(saved.targetVersion()) && !saved.scanCompleted()
                        ? new RebuildReport(version, saved.cursor(), saved.counts(), false, null)
                        : new RebuildReport(version, 0, new ContentVectorBuilder.Counts(0, 0, 0, 0), false, null);
                checkpoint(report, lease);
                while (true) {
                    lease.assertOwned();
                    long cursor = report.cursor();
                    SearchResponse<Void> response = elasticsearchClient.search(search -> {
                        search.index(CONTENT_INDEX).size(REBUILD_BATCH)
                                .query(query -> query.bool(bool -> bool.filter(filter -> filter.term(term -> term.field("status").value("published")))
                                        .filter(filter -> filter.term(term -> term.field("visibility").value("public")))
                                        .mustNot(filter -> filter.term(term -> term.field("vectorVersion").value(version)))))
                                .sort(sort -> sort.field(field -> field.field("id")
                                        .order(co.elastic.clients.elasticsearch._types.SortOrder.Asc)));
                        if (cursor > 0) search.searchAfter(List.of(FieldValue.of(cursor)));
                        return search;
                    }, Void.class);
                    List<Hit<Void>> hits = response.hits().hits();
                    if (hits.isEmpty()) break;
                    ContentVectorBuilder.Counts counts = builder.batch(
                            hits.stream().map(hit -> Long.valueOf(hit.id())).toList(),
                            version);
                    RebuildReport next = new RebuildReport(version, Long.parseLong(hits.get(hits.size() - 1).id()),
                            report.counts().plus(counts), false, null);
                    checkpoint(next, lease);
                    report = next;
                }
                report = new RebuildReport(version, report.cursor(), report.counts(), true, null);
                checkpoint(report, lease);
                lease.complete(IdempotentGuard.DONE, 0);
                log.info("向量扫描完成 targetVersion={} succeeded={} existing={} skipped={} handedToRecovery={}", version,
                        report.counts()
                                .succeeded(), report.counts()
                                .existing(), report.counts()
                                .skipped(), report.counts()
                                .pendingRecovery());
            } catch (Exception error) {
                if (report != null) {
                    String reason = error.getClass().getSimpleName() + ": " + error.getMessage();
                    try {
                        checkpoint(new RebuildReport(report.targetVersion(), report.cursor(), report.counts(), false,
                                reason.substring(0, Math.min(reason.length(), 500))), lease);
                    } catch (RuntimeException saveError) {
                        error.addSuppressed(saveError);
                    }
                }
                log.error("向量扫描中断，保留最后已确认检查点；未完成批次将重试 cursor={}", report == null ? null : report.cursor(), error);
            }
        } catch (RuntimeException conflict) {
            log.warn("向量重建未启动，执行占位不可用: {}", conflict.getMessage());
        } finally {
            rebuildRunning.set(false);
        }
    }

    private void checkpoint(RebuildReport report, IdempotentGuard.Lease lease) {
        lease.checkActive();
        Long saved = redisTemplate.execute(SAVE_CHECKPOINT,
                List.of(cn.diaojiahong.myyoutube.common.constants.RedisConstants.IDEMPOTENT + REBUILD_KEY, CHECKPOINT_KEY),
                lease.token(), JsonUtil.toJson(report));
        if (!Long.valueOf(1).equals(saved)) throw new IllegalStateException("向量重建检查点未获确认");
    }
}
