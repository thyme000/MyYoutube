package cn.diaojiahong.myyoutube.common.observability;

import java.nio.charset.StandardCharsets;
import java.util.*;

public final class OutboxBatches {
    private static final String PREFIX = "{\"schemaVersion\":1,\"events\":[";
    private static final String SUFFIX = "]}";
    public record Batch(List<OutboxStore.Entry> entries, byte[] body) {}
    public record OutboxBatchResult(List<Batch> batches, List<OutboxStore.Entry> oversized) {}
    private OutboxBatches() {}

    public static OutboxBatchResult split(List<OutboxStore.Entry> rows, int maxEvents, int maxBytes) {
        if (maxEvents < 1 || maxBytes < 1) throw new IllegalArgumentException("Invalid batch limits");
        var batches = new ArrayList<Batch>();
        var oversized = new ArrayList<OutboxStore.Entry>();
        var current = new ArrayList<OutboxStore.Entry>();
        int envelope = utf8(PREFIX).length + utf8(SUFFIX).length, bytes = envelope;
        for (var row : rows) {
            int size = utf8(row.payload()).length;
            if ((long) envelope + size > maxBytes) { oversized.add(row); continue; }
            if (!current.isEmpty() && (current.size() >= maxEvents || (long) bytes + 1 + size > maxBytes)) {
                batches.add(batch(current)); current.clear(); bytes = envelope;
            }
            bytes += size + (current.isEmpty() ? 0 : 1);
            current.add(row);
        }
        if (!current.isEmpty()) batches.add(batch(current));
        return new OutboxBatchResult(List.copyOf(batches), List.copyOf(oversized));
    }
    private static Batch batch(List<OutboxStore.Entry> entries) {
        String json = PREFIX + String.join(",", entries.stream().map(OutboxStore.Entry::payload).toList()) + SUFFIX;
        return new Batch(List.copyOf(entries), utf8(json));
    }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
