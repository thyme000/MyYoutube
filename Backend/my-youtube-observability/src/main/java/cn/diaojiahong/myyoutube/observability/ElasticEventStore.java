package cn.diaojiahong.myyoutube.observability;

import cn.diaojiahong.myyoutube.common.observability.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static cn.diaojiahong.myyoutube.observability.IngestFailure.Reason.*;

public final class ElasticEventStore {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private final URI endpoint;
    private final String prefix;
    private final String policy;
    private final HttpClient http;
    private final Clock clock;
    private final MeterRegistry metrics;
    record Document(PipelineEvent event, String index, String digest, ObjectNode source) {}
    record Response(int status, JsonNode body) {}

    public ElasticEventStore(IngestProperties properties, Clock clock, MeterRegistry metrics) {
        endpoint = properties.getElasticsearch();
        if (!Set.of("http", "https").contains(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null)
            throw new IllegalArgumentException("Invalid Elasticsearch endpoint");
        prefix = properties.getIndexPrefix();
        if (!prefix.matches("[a-z][a-z0-9-]{1,100}-")) throw new IllegalArgumentException("Invalid index prefix");
        policy = prefix + "retention";
        http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        this.clock = clock;
        this.metrics = metrics;
    }

    public void initialize() {
        required("PUT", "/_ilm/policy/" + policy,
                "{\"policy\":{\"phases\":{\"delete\":{\"min_age\":\"8d\",\"actions\":{\"delete\":{}}}}}}");
        try (InputStream in = getClass().getResourceAsStream("/observability/pipeline-event-mappings.json")) {
            if (in == null) throw new IllegalStateException("Missing event mapping");
            ObjectNode template = EventBatch.JSON.createObjectNode();
            template.putArray("index_patterns").add(prefix + "*");
            template.put("priority", 400).put("allow_auto_create", false);
            ObjectNode definition = template.putObject("template");
            definition.set("settings", EventBatch.JSON.valueToTree(Map.of("number_of_shards", 1,
                    "number_of_replicas", 0, "refresh_interval", "1s", "index.lifecycle.name", policy)));
            definition.set("mappings", EventBatch.JSON.readTree(in));
            required("PUT", "/_index_template/" + prefix + "template", template.toString());
        } catch (IOException e) { throw new IllegalStateException("Cannot load event mapping", e); }
    }

    public void ingest(byte[] batch) {
        List<PipelineEvent> events = EventBatch.decode(batch);
        List<Document> documents = events.stream().map(this::document).toList();
        Map<String, Instant> days = new LinkedHashMap<>();
        documents.forEach(d -> days.put(d.index(), dayStart(d.event().occurredAt())));
        days.forEach(this::ensureIndex);
        documents.forEach(d -> checkAge(dayStart(d.event().occurredAt())));
        StringBuilder bulk = new StringBuilder();
        for (Document d : documents) {
            bulk.append("{\"create\":{\"_index\":\"").append(d.index()).append("\",\"_id\":\"")
                    .append(d.event().event().id()).append("\"}}\n").append(d.source()).append('\n');
        }
        long start = System.nanoTime();
        Response response;
        try { response = request("POST", "/_bulk", bulk.toString(), "application/x-ndjson"); }
        finally { metrics.timer("pipeline.ingest.bulk.duration").record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS); }
        if (response.status() == 400 || response.status() == 413) throw failure(MAPPING);
        if (response.status() != 200) throw failure(UNAVAILABLE);
        JsonNode items = response.body().path("items");
        if (!items.isArray() || items.size() != documents.size()) throw failure(UNAVAILABLE);
        List<Document> duplicates = new ArrayList<>();
        boolean transientError = false;
        boolean permanentError = false;
        for (int i = 0; i < documents.size(); i++) {
            Document d = documents.get(i);
            JsonNode item = items.get(i).path("create");
            if (!d.index().equals(item.path("_index").asText()) || !d.event().event().id().equals(item.path("_id").asText()))
                throw failure(UNAVAILABLE);
            int status = item.path("status").asInt();
            if (status == 409) duplicates.add(d);
            else if (status == 400 || status == 413) permanentError = true;
            else if (status != 201) transientError = true;
        }
        // 已知的永久写入错误不能因无法查询重复项而反复写入。
        if (permanentError) throw failure(MAPPING);
        if (!duplicates.isEmpty()) verifyDuplicates(duplicates);
        if (transientError) throw failure(UNAVAILABLE);
        metrics.counter("pipeline.ingest.batches", "result", "success").increment();
    }

    private Document document(PipelineEvent event) {
        Instant start = dayStart(event.occurredAt());
        checkAge(start);
        try {
            ObjectNode source = (ObjectNode) EventBatch.JSON.readTree(PipelineEventJson.encode(event));
            String digest = PipelineEventJson.digest(event);
            source.putObject("ingest").put("digest", digest).put("schemaVersion", 1);
            return new Document(event, prefix + DAY.format(start.atZone(ZoneOffset.UTC)), digest, source);
        } catch (IOException e) { throw failure(INVALID_BATCH); }
    }

    private void verifyDuplicates(List<Document> documents) {
        ObjectNode query = EventBatch.JSON.createObjectNode();
        for (Document d : documents) query.withArray("docs").addObject().put("_index", d.index()).put("_id", d.event().event().id());
        Response response = request("POST", "/_mget", query.toString(), "application/json");
        JsonNode docs = response.body().path("docs");
        if (response.status() != 200 || !docs.isArray() || docs.size() != documents.size()) {
            metrics.counter("pipeline.ingest.duplicates", "result", "unknown").increment();
            throw failure(UNAVAILABLE);
        }
        boolean unknown = false;
        boolean conflict = false;
        for (int i = 0; i < documents.size(); i++) {
            Document d = documents.get(i);
            JsonNode found = docs.get(i);
            if (!d.index().equals(found.path("_index").asText()) || !d.event().event().id().equals(found.path("_id").asText())
                    || found.has("error") || !found.path("found").asBoolean()) {
                unknown = true;
                continue;
            }
            JsonNode source = found.path("_source");
            try {
                if (!source.isObject()) throw new IllegalArgumentException();
                ObjectNode original = ((ObjectNode) source).deepCopy();
                JsonNode metadata = original.remove("ingest");
                PipelineEvent existing = PipelineEventJson.decode(original.toString());
                boolean same = existing.event().id().equals(d.event().event().id())
                        && existing.event().sequence() == d.event().event().sequence()
                        && existing.aggregate().equals(d.event().aggregate())
                        && metadata != null && metadata.path("schemaVersion").asInt() == 1
                        && d.digest().equals(metadata.path("digest").asText())
                        && d.digest().equals(PipelineEventJson.digest(existing));
                if (!same) conflict = true;
            } catch (IllegalArgumentException e) { conflict = true; }
        }
        String result = conflict ? "conflict" : unknown ? "unknown" : "same";
        metrics.counter("pipeline.ingest.duplicates", "result", result).increment(documents.size());
        if (conflict) throw failure(CONFLICT);
        if (unknown) throw failure(UNAVAILABLE);
    }

    private void ensureIndex(String index, Instant day) {
        checkAge(day);
        Response existing = request("GET", "/" + index + "/_settings", null, "application/json");
        if (existing.status() == 404) {
            String settings = "{\"settings\":{\"index.lifecycle.name\":\"" + policy
                    + "\",\"index.lifecycle.origination_date\":" + day.toEpochMilli() + "}}";
            Response created = request("PUT", "/" + index, settings, "application/json");
            if (created.status() != 200 && !(created.status() == 400 && "resource_already_exists_exception".equals(
                    created.body().path("error").path("type").asText()))) throw failure(UNAVAILABLE);
            existing = request("GET", "/" + index + "/_settings", null, "application/json");
        }
        JsonNode lifecycle = existing.body().path(index).path("settings").path("index").path("lifecycle");
        if (existing.status() != 200 || !policy.equals(lifecycle.path("name").asText())
                || lifecycle.path("origination_date").asLong(-1) != day.toEpochMilli()) throw failure(UNAVAILABLE);
    }

    private static Instant dayStart(Instant timestamp) { return timestamp.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant(); }
    private void checkAge(Instant day) { if (!clock.instant().isBefore(day.plus(Duration.ofDays(8)))) throw failure(EXPIRED_BUCKET); }
    private IngestFailure failure(IngestFailure.Reason reason) {
        metrics.counter("pipeline.ingest.failures", "reason", reason.name()).increment();
        return new IngestFailure(reason);
    }
    private void required(String method, String path, String body) {
        if (request(method, path, body, "application/json").status() != 200) throw failure(UNAVAILABLE);
    }
    private Response request(String method, String path, String body, String type) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint.toString().replaceAll("/$", "") + path))
                    .timeout(TIMEOUT).header("Content-Type", type).method(method, body == null
                            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), EventBatch.JSON.readTree(response.body()));
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw failure(UNAVAILABLE); }
        catch (IOException | IllegalArgumentException e) { throw failure(UNAVAILABLE); }
    }
}
