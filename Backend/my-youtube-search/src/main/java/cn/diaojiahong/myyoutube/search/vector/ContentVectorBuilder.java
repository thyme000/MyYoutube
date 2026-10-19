package cn.diaojiahong.myyoutube.search.vector;

import cn.diaojiahong.myyoutube.common.idempotent.IdempotentGuard;
import cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.json.JsonData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContentVectorBuilder {
    static final String EXECUTION_KEY = "{content-vector}:";
    private static final List<String> FIELDS = List.of("title", "description", "summary", "status", "visibility", "vectorVersion");
    private static final int WRITE_ATTEMPTS = 3;
    private final LlmPort llm;
    private final ElasticsearchClient es;
    private final ContentVectorPublisher publisher;
    private final IdempotentGuard guard;
    private final VectorProperties vectorProperties;

    enum Outcome { SUCCEEDED, EXISTING, SKIPPED }
    private record Snapshot(Long id, Map<String, Object> source, long seqNo, long primaryTerm) {}
    private record VectorWrite(Snapshot original, Snapshot expected, List<Float> vector) {}

    public record Counts(long succeeded, long existing, long skipped, long pendingRecovery) {
        public Counts plus(Counts other) {
            return new Counts(succeeded + other.succeeded, existing + other.existing,
                    skipped + other.skipped, pendingRecovery + other.pendingRecovery);
        }
        private Counts add(Outcome outcome) {
            return plus(new Counts(outcome == Outcome.SUCCEEDED ? 1 : 0, outcome == Outcome.EXISTING ? 1 : 0,
                    outcome == Outcome.SKIPPED ? 1 : 0, 0));
        }
    }

    public void single(Long id, String targetVersion, IdempotentGuard.Lease lease) {
        Snapshot content = fetch(id);
        if (ineligible(content)) return;
        if (completed(content, targetVersion)) return;
        lease.assertOwned();
        LlmPort.EmbeddingsResponse response = generate(List.of(text(content)), targetVersion);
        lease.assertOwned();
        var item = response.getItems().get(0);
        if (response.getUnavailableReason() != null) throw unavailable(response);
        if (item.error() != null) throw new IllegalStateException(item.error());
        Outcome outcome = write(content, item.vector(), response.getVersion(), lease);
        log.info("Content vector completed contentId={} version={} outcome={}", id, response.getVersion(), outcome);
    }

    public Counts batch(List<Long> ids, String version) {
        try (var lease = guard.acquireAll(ids.stream().map(id -> EXECUTION_KEY + id).toList(), IdempotentGuard.DEFAULT_LEASE_SECONDS)) {
            if (!lease.acquired()) throw new IllegalStateException("向量批量占位状态异常");
            Map<Long, Snapshot> contents = fetchAll(ids);
            Counts counts = new Counts(0, 0, 0, 0);
            List<Snapshot> pending = new ArrayList<>();
            for (Long id : ids) {
                Snapshot content = contents.get(id);
                if (ineligible(content)) counts = counts.add(Outcome.SKIPPED);
                else if (completed(content, version)) counts = counts.add(Outcome.EXISTING);
                else pending.add(content);
            }
            if (pending.isEmpty()) {
                lease.complete(IdempotentGuard.DONE, 0);
                return counts;
            }
            lease.assertOwned();
            var response = generate(pending.stream().map(this::text).toList(), version);
            List<VectorWrite> writes = new ArrayList<>();
            for (var item : response.getItems()) {
                Snapshot content = pending.get(item.index());
                if (item.error() == null) writes.add(new VectorWrite(content, content, item.vector()));
            }
            counts = writeAll(writes, version, counts, lease);
            if (response.getUnavailableReason() != null) throw unavailable(response);
            for (var item : response.getItems()) {
                if (item.error() != null) counts = registerFailure(pending.get(item.index()).id(), version,
                        new IllegalStateException(item.error()), counts, lease);
            }
            lease.complete(IdempotentGuard.DONE, 0);
            return counts;
        }
    }

    private Counts registerFailure(Long id, String version, RuntimeException error, Counts counts, IdempotentGuard.Lease lease) {
        lease.assertOwned();
        String reason = error.getClass().getSimpleName() + ": " + error.getMessage();
        publisher.publishRecovery(id, version, reason.substring(0, Math.min(reason.length(), 500)));
        log.warn("Vector recovery handed to MQ contentId={} targetVersion={} reason={}", id, version, reason);
        return counts.plus(new Counts(0, 0, 0, 1));
    }

    private LlmPort.EmbeddingsResponse generate(List<String> texts, String targetVersion) {
        var response = llm.embeddings(new LlmPort.EmbeddingsRequest(texts), vectorProperties.embeddingOptions(texts.size()));
        if (response == null || response.getVersion() == null || response.getVersion().isBlank()
                || response.getDims() == null || response.getDims() <= 0 || response.getItems() == null
                || response.getItems().size() != texts.size()) throw new IllegalStateException("模型向量响应结构不完整");
        if (targetVersion != null && !targetVersion.equals(response.getVersion())) {
            throw new VersionChanged(targetVersion, response.getVersion());
        }
        boolean[] seen = new boolean[texts.size()];
        List<LlmPort.EmbeddingItem> checked = new ArrayList<>();
        for (var item : response.getItems()) {
            if (item == null || item.index() < 0 || item.index() >= texts.size() || seen[item.index()]
                    || (item.vector() == null) == (item.error() == null)) {
                throw new IllegalStateException("模型向量响应位置或结果非法");
            }
            seen[item.index()] = true;
            List<Float> vector = item.vector();
            checked.add(vector != null && (vector.size() != response.getDims()
                    || vector.stream().anyMatch(value -> value == null || !Float.isFinite(value)))
                    ? new LlmPort.EmbeddingItem(item.index(), null, "模型向量维度或数值非法") : item);
        }
        response.setItems(checked);
        return response;
    }

    private ModelUnavailable unavailable(LlmPort.EmbeddingsResponse response) {
        return new ModelUnavailable("LLM", "MODEL_UPSTREAM_UNAVAILABLE", 30,
                new IllegalStateException(response.getUnavailableReason()));
    }

    private Counts writeAll(List<VectorWrite> pending, String version, Counts counts, IdempotentGuard.Lease lease) {
        for (int attempt = 1; attempt <= WRITE_ATTEMPTS && !pending.isEmpty(); attempt++) {
            lease.assertOwned();
            var request = new BulkRequest.Builder();
            for (var item : pending) {
                var expected = item.expected();
                request.operations(operation -> operation.update(update -> update.index("content").id(expected.id().toString())
                        .ifSeqNo(expected.seqNo()).ifPrimaryTerm(expected.primaryTerm())
                        .action(action -> action.doc(JsonData.of(Map.of("vector", item.vector(), "vectorVersion", version))))));
            }
            BulkResponse response = null;
            RuntimeException transportError = null;
            try {
                response = es.bulk(request.build());
            } catch (IOException | ElasticsearchException error) {
                transportError = new IllegalStateException("批量向量写回结果未确认", error);
            }
            if (response != null) {
                if (response.items().size() != pending.size()) throw new IllegalStateException("批量写回响应缺失");
                for (int i = 0; i < pending.size(); i++) {
                    if (!pending.get(i).original().id().toString().equals(response.items().get(i).id())) {
                        throw new IllegalStateException("批量写回响应位置不匹配");
                    }
                }
            } else if (transportError == null) throw new IllegalStateException("批量写回响应缺失");
            List<VectorWrite> failed = new ArrayList<>();
            Map<Long, RuntimeException> errors = new LinkedHashMap<>();
            for (int i = 0; i < pending.size(); i++) {
                var item = pending.get(i);
                var result = response == null ? null : response.items().get(i);
                if (result != null && result.error() == null && result.status() >= 200 && result.status() < 300) {
                    counts = counts.add(Outcome.SUCCEEDED);
                } else {
                    RuntimeException error = transportError != null ? transportError
                            : new IllegalStateException("向量写回失败: status=" + result.status() + " error=" + result.error());
                    if (result != null && result.status() != 409 && result.status() != 429 && result.status() < 500) {
                        counts = registerFailure(item.original().id(), version, error, counts, lease);
                    } else {
                        failed.add(item);
                        errors.put(item.original().id(), error);
                    }
                }
            }
            if (failed.isEmpty()) break;
            lease.assertOwned();
            var latest = fetchAll(failed.stream().map(item -> item.original().id()).toList());
            List<VectorWrite> retry = new ArrayList<>();
            for (var item : failed) {
                var original = item.original();
                var current = latest.get(original.id());
                if (ineligible(current)) counts = counts.add(Outcome.SKIPPED);
                else if (completed(current, version)) counts = counts.add(Outcome.EXISTING);
                else if (!text(original).equals(text(current)) || !Objects.equals(
                        original.source().get("vectorVersion"), current.source().get("vectorVersion"))) {
                    counts = registerFailure(original.id(), version,
                            new IllegalStateException("构建期间内容或向量版本已变化, contentId=" + original.id()), counts, lease);
                } else if (attempt == WRITE_ATTEMPTS) {
                    counts = registerFailure(original.id(), version, errors.get(original.id()), counts, lease);
                } else retry.add(new VectorWrite(original, current, item.vector()));
            }
            pending = retry;
        }
        return counts;
    }

    private Outcome write(Snapshot original, List<Float> vector, String version, IdempotentGuard.Lease lease) {
        Snapshot current = original;
        RuntimeException last = null;
        for (int attempt = 1; attempt <= WRITE_ATTEMPTS; attempt++) {
            lease.checkActive();
            try {
                Snapshot expected = current;
                es.update(update -> update.index("content").id(expected.id().toString())
                        .ifSeqNo(expected.seqNo()).ifPrimaryTerm(expected.primaryTerm())
                        .doc(JsonData.of(Map.of("vector", vector, "vectorVersion", version))), Void.class);
                return Outcome.SUCCEEDED;
            } catch (IOException | ElasticsearchException error) {
                last = new IllegalStateException("向量写回失败, contentId=" + original.id(), error);
                if (attempt == WRITE_ATTEMPTS) break;
                lease.assertOwned();
                current = fetch(original.id());
                if (ineligible(current)) return Outcome.SKIPPED;
                if (completed(current, version)) return Outcome.EXISTING;
                if (!text(original).equals(text(current)) || !Objects.equals(
                        original.source().get("vectorVersion"), current.source().get("vectorVersion"))) {
                    throw new IllegalStateException("构建期间内容或向量版本已变化, contentId=" + original.id(), error);
                }
                log.warn("Vector write retry contentId={} attempt={}", original.id(), attempt);
            }
        }
        throw last;
    }

    private Snapshot fetch(Long id) {
        try {
            var response = es.get(get -> get.index("content").id(id.toString()).sourceIncludes(FIELDS), Map.class);
            return response.found() ? snapshot(id, response.source(), response.seqNo(), response.primaryTerm()) : null;
        } catch (IOException error) {
            throw new IllegalStateException("向量文档读取失败, contentId=" + id, error);
        }
    }

    private Map<Long, Snapshot> fetchAll(List<Long> ids) {
        try {
            var response = es.mget(get -> get.index("content").ids(ids.stream().map(Object::toString).toList())
                    .sourceIncludes(FIELDS), Map.class);
            Map<Long, Snapshot> contents = new LinkedHashMap<>();
            for (var item : response.docs()) {
                if (!item.isResult()) throw new IllegalStateException("批量内容状态读取失败");
                var result = item.result();
                contents.put(Long.valueOf(result.id()), result.found()
                        ? snapshot(Long.valueOf(result.id()), result.source(), result.seqNo(), result.primaryTerm()) : null);
            }
            if (!contents.keySet().containsAll(ids)) throw new IllegalStateException("批量内容状态响应缺失");
            return contents;
        } catch (IOException error) {
            throw new IllegalStateException("批量向量文档读取失败", error);
        }
    }

    private Snapshot snapshot(Long id, Map<String, Object> source, Long seqNo, Long primaryTerm) {
        if (source == null || seqNo == null || seqNo < 0 || primaryTerm == null || primaryTerm <= 0) {
            throw new IllegalStateException("内容状态或并发版本缺失, contentId=" + id);
        }
        return new Snapshot(id, new LinkedHashMap<>(source), seqNo, primaryTerm);
    }

    private boolean ineligible(Snapshot content) {
        return content == null || !"published".equals(content.source().get("status"))
                || !"public".equals(content.source().get("visibility")) || text(content).isEmpty();
    }

    private boolean completed(Snapshot content, String version) {
        Object actual = content.source().get("vectorVersion");
        return version == null ? actual instanceof String value && !value.isBlank() : version.equals(actual);
    }

    private String text(Snapshot content) {
        return Stream.of("title", "description", "summary").map(content.source()::get).filter(Objects::nonNull)
                .map(Object::toString).map(String::trim).filter(value -> !value.isEmpty()).collect(Collectors.joining("\n"));
    }

    private static class VersionChanged extends IllegalStateException {
        VersionChanged(String expected, String actual) { super("目标向量版本已变化: expected=" + expected + ", actual=" + actual); }
    }
}
