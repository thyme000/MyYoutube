package cn.diaojiahong.myyoutube.observability;

import cn.diaojiahong.myyoutube.common.observability.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static cn.diaojiahong.myyoutube.observability.IngestFailure.Reason.*;

class ElasticEventStoreTest {
    HttpServer server;
    ElasticEventStore store;
    List<String> paths;
    String bulkStatus;
    int mgetStatus;
    String mgetBody;
    String index;
    Instant now = Instant.parse("2026-10-14T10:00:00Z");
    @BeforeEach void setup() throws Exception {
        paths = new ArrayList<>(); bulkStatus = "201,201"; mgetStatus = 200;
        index = "test-events-2026.10.14";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath(); paths.add(exchange.getRequestMethod() + " " + path);
            String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String body = "{}"; int status = 200;
            if (path.endsWith("_settings")) body = "{\"" + index + "\":{\"settings\":{\"index\":{\"lifecycle\":{\"name\":\"test-events-retention\",\"origination_date\":1791936000000}}}}}";
            if (path.equals("/_bulk")) {
                assertFalse(exchange.getRequestURI().toString().contains("refresh"));
                String[] lines = payload.split("\n");
                var result = EventBatch.JSON.createObjectNode();
                var items = result.putArray("items");
                String[] statuses = bulkStatus.split(",");
                for (int i = 0; i < statuses.length; i++) {
                    var action = EventBatch.JSON.readTree(lines[2 * i]).path("create");
                    items.addObject().putObject("create").put("_index", action.path("_index").asText())
                            .put("_id", action.path("_id").asText()).put("status", Integer.parseInt(statuses[i]));
                }
                body = result.toString();
            }
            if (path.equals("/_mget")) { status = mgetStatus; body = mgetBody; }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        IngestProperties properties = new IngestProperties();
        properties.setElasticsearch(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        properties.setIndexPrefix("test-events-");
        store = new ElasticEventStore(properties, Clock.fixed(now, ZoneOffset.UTC), new SimpleMeterRegistry());
    }
    @AfterEach void close() { server.stop(0); }
    static PipelineEvent event(String id, long sequence, Instant time) {
        return new PipelineEvent(time, new PipelineEvent.Service("transcode"), new PipelineEvent.Event(id, "ASR_STARTED", sequence),
                new PipelineEvent.Aggregate("TASK", "9007199254740993"), null, "1", "2", "9007199254740993", null, null, null,
                1, null, null, null, null, new PipelineEvent.State("READY", "RUNNING"), null);
    }
    PipelineEvent event(String id, long sequence) { return event(id, sequence, now); }
    static byte[] batch(PipelineEvent... events) {
        return ("{\"schemaVersion\":1,\"events\":[" + String.join(",", Arrays.stream(events).map(PipelineEventJson::encode).toList()) + "]}").getBytes(StandardCharsets.UTF_8);
    }
    void found(PipelineEvent existing, String digest) throws Exception {
        ObjectNode source = (ObjectNode) EventBatch.JSON.readTree(PipelineEventJson.encode(existing));
        source.putObject("ingest").put("digest", digest).put("schemaVersion", 1);
        ObjectNode result = EventBatch.JSON.createObjectNode();
        result.putArray("docs").addObject().put("_index", index).put("_id", "first").put("found", true).set("_source", source);
        mgetBody = result.toString();
    }
    @Test void partialTemporaryFailureRetriesWholeBatchAndVerifies409() throws Exception {
        bulkStatus = "201,429";
        byte[] batch = batch(event("first", 1), event("second", 2));
        assertEquals(UNAVAILABLE, assertThrows(IngestFailure.class, () -> store.ingest(batch)).reason());
        bulkStatus = "409,201"; found(event("first", 1), PipelineEventJson.digest(event("first", 1)));
        store.ingest(batch);
        assertEquals(2, paths.stream().filter(p -> p.equals("POST /_bulk")).count());
        assertEquals(1, paths.stream().filter(p -> p.equals("POST /_mget")).count());
    }
    @Test void sameIdDifferentContentIsConflictEvenWithForgedDigest() throws Exception {
        bulkStatus = "409"; found(event("first", 2), PipelineEventJson.digest(event("first", 1)));
        assertEquals(CONFLICT, assertThrows(IngestFailure.class, () -> store.ingest(batch(event("first", 1)))).reason());
    }
    @Test void mgetFailureAndMissingDocumentAreUnknown() {
        bulkStatus = "409"; mgetStatus = 503; mgetBody = "{}";
        assertEquals(UNAVAILABLE, assertThrows(IngestFailure.class, () -> store.ingest(batch(event("first", 1)))).reason());
        mgetStatus = 200; mgetBody = "{\"docs\":[{\"_index\":\"" + index + "\",\"_id\":\"first\",\"found\":false}]}";
        assertEquals(UNAVAILABLE, assertThrows(IngestFailure.class, () -> store.ingest(batch(event("first", 1)))).reason());
    }
    @Test void permanent400DominatesTransientAndDoesNotRequireMget() {
        bulkStatus = "409,400"; mgetStatus = 503;
        assertEquals(MAPPING, assertThrows(IngestFailure.class, () -> store.ingest(batch(event("first", 1), event("second", 2)))).reason());
        assertFalse(paths.contains("POST /_mget"));
    }
    @Test void expiredBucketIsRejectedBeforeAnyHttp() {
        assertEquals(EXPIRED_BUCKET, assertThrows(IngestFailure.class,
                () -> store.ingest(batch(event("old", 1, Instant.parse("2026-10-06T23:59:59Z"))))).reason());
        assertTrue(paths.isEmpty());
    }
    @Test void strictEnvelopeAndEventRejectUnknownFieldsCoercionAndDuplicateKeys() {
        String valid = new String(batch(event("first", 1)), StandardCharsets.UTF_8);
        for (String invalid : List.of(valid.replace("\"taskId\":\"9007199254740993\"", "\"taskId\":9007199254740993"),
                valid.replace("\"sequence\":1", "\"sequence\":\"1\""), valid.replace("\"attempt\":1", "\"attempt\":1.5"),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
                valid.replace("\"service\":", "\"token\":\"hidden\",\"service\":"), valid + "{}")) {
            assertEquals(INVALID_BATCH, assertThrows(IngestFailure.class,
                    () -> store.ingest(invalid.getBytes(StandardCharsets.UTF_8)), invalid).reason());
        }
    }
    @Test void templateDisablesAutomaticRecreation() {
        store.initialize();
        assertTrue(paths.contains("PUT /_ilm/policy/test-events-retention"));
        assertTrue(paths.contains("PUT /_index_template/test-events-template"));
    }
}
