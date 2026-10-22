package cn.diaojiahong.myyoutube.search.hot;

import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

@Component
public class HotSearchStore {
    static final String PREFIX = "{hot-search}:";
    static final long WINDOW_MILLIS = Duration.ofHours(24).toMillis();
    static final long PENDING_MILLIS = Duration.ofDays(3).toMillis();
    private static final ZoneId STAT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int RANK_BATCH_SIZE = 32;
    private static final int ACK_BATCH_SIZE = 200;
    private static final RedisScript<Long> RECORD = RedisScript.of(new ClassPathResource("lua/hot-search-record.lua"), Long.class);
    private static final RedisScript<Long> CONTRIBUTE = RedisScript.of(new ClassPathResource("lua/hot-search-contribute.lua"), Long.class);
    private static final RedisScript<Long> ALIAS = RedisScript.of(new ClassPathResource("lua/hot-search-alias.lua"), Long.class);
    private static final RedisScript<Long> ACK = RedisScript.of(new ClassPathResource("lua/hot-search-ack.lua"), Long.class);
    private final StringRedisTemplate redis;
    private final HotSearchProperties properties;
    private final int partitionCount;
    private final AtomicInteger pendingCursor = new AtomicInteger();

    public HotSearchStore(StringRedisTemplate redis, HotSearchProperties properties) {
        this.redis = redis;
        this.properties = properties;
        // 固定本实例的路由；配置刷新不能把未搬完的输入切到另一套分区。
        this.partitionCount = properties.getPartitionCount();
    }

    public record Event(String token, String keyword, String rawKeyword, long userId, long happenedAt) {}
    public record Assignment(String groupId, String groupName, String version, String statDate) {}
    private record Alias(String groupId, String version) {}
    public record Ranking(HotSearchGroup group, int score, int users) {}
    private record Snapshot(List<String> names) {}
    private record PendingCursor(int partition, int offset) {}

    static String digest(String text) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    public boolean record(Event event) {
        int partition = partition(event.token());
        return Long.valueOf(1).equals(redis.execute(RECORD, List.of(pendingKey(partition)),
                JsonUtil.toJson(event), String.valueOf(event.happenedAt()), String.valueOf(PENDING_MILLIS),
                String.valueOf(quota(properties.getPendingLimit(), partition))));
    }

    public List<Event> pending(long now) {
        int start = Math.floorMod(pendingCursor.getAndIncrement(), partitionCount);
        var cursors = new ArrayDeque<PendingCursor>();
        for (int i = 0; i < partitionCount; i++) cursors.add(new PendingCursor((start + i) % partitionCount, 0));
        Set<Event> events = new LinkedHashSet<>();
        while (!cursors.isEmpty() && events.size() < properties.getBatchSize()) {
            int remaining = properties.getBatchSize() - events.size();
            int count = Math.min(cursors.size(), remaining);
            List<PendingCursor> reads = new ArrayList<>();
            int[] limits = new int[count];
            for (int i = 0; i < count; i++) {
                reads.add(cursors.remove());
                limits[i] = remaining / count + (i < remaining % count ? 1 : 0);
            }
            var results = pipeline(operations -> {
                for (int i = 0; i < reads.size(); i++) {
                    var cursor = reads.get(i);
                    String key = pendingKey(cursor.partition());
                    if (cursor.offset() == 0) operations.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, now - PENDING_MILLIS);
                    operations.opsForZSet().range(key, cursor.offset(), cursor.offset() + limits[i] - 1);
                }
            });
            int resultIndex = 0;
            for (int i = 0; i < reads.size(); i++) {
                var cursor = reads.get(i);
                if (cursor.offset() == 0) resultIndex++;
                var raw = (Collection<?>) results.get(resultIndex++);
                if (raw == null) continue;
                raw.forEach(value -> events.add(JsonUtil.fromJson((String) value, Event.class)));
                // 空闲分区的额度让给仍有积压的分区，避免有数据却只能取到一小份。
                if (raw.size() == limits[i]) cursors.add(new PendingCursor(cursor.partition(), cursor.offset() + raw.size()));
            }
        }
        return List.copyOf(events);
    }

    public void remove(Event event) {
        remove(List.of(event));
    }

    public void remove(Collection<Event> events) {
        byPartition(events, event -> partition(event.token())).forEach((partition, batch) -> {
            for (int from = 0; from < batch.size(); from += ACK_BATCH_SIZE) {
                var chunk = batch.subList(from, Math.min(from + ACK_BATCH_SIZE, batch.size()));
                List<String> keys = new ArrayList<>();
                keys.add(pendingKey(partition));
                chunk.forEach(event -> keys.add(assignmentKey(event)));
                redis.execute(ACK, keys, chunk.stream().map(JsonUtil::toJson).toArray(String[]::new));
            }
        });
    }

    public Assignment assignment(Event event) {
        return assignments(List.of(event)).get(event);
    }

    public Map<Event, Assignment> assignments(List<Event> events) {
        if (events.isEmpty()) return Map.of();
        var batches = new ArrayList<>(byPartition(events, event -> partition(event.token())).values());
        var results = pipeline(operations -> batches.forEach(batch ->
                operations.opsForValue().multiGet(batch.stream().map(this::assignmentKey).toList())));
        Map<Event, Assignment> result = new LinkedHashMap<>();
        for (int b = 0; b < batches.size(); b++) {
            var batch = batches.get(b);
            var values = (List<?>) results.get(b);
            if (values == null || values.size() != batch.size()) throw new IllegalStateException("Hot search assignments unavailable");
            for (int i = 0; i < batch.size(); i++) {
                if (values.get(i) != null) result.put(batch.get(i), JsonUtil.fromJson((String) values.get(i), Assignment.class));
            }
        }
        return result;
    }

    public Assignment assign(Event event, HotSearchGroup group, long now) {
        return assign(group, List.of(event), now).get(event);
    }

    public Map<Event, Assignment> assign(HotSearchGroup group, List<Event> events, long now) {
        if (events.isEmpty()) return Map.of();
        Map<Event, Assignment> proposed = new LinkedHashMap<>();
        for (var event : events) {
            if (event.happenedAt() + PENDING_MILLIS <= now) throw new IllegalStateException("Hot search pending event expired");
            proposed.put(event, new Assignment(group.id(), group.name(), group.version(),
                    Instant.ofEpochMilli(event.happenedAt()).atZone(STAT_ZONE).toLocalDate().toString()));
        }
        pipeline(operations -> proposed.forEach((event, assignment) -> operations.opsForValue().setIfAbsent(
                assignmentKey(event), JsonUtil.toJson(assignment),
                Duration.ofMillis(event.happenedAt() + PENDING_MILLIS - now))));
        // NX 冲突时必须读取已固定的归属，不能把本次提议当成已经写入的结果。
        var result = assignments(events);
        if (result.size() != events.size()) throw new IllegalStateException("Hot search assignments unconfirmed");
        return result;
    }

    public HotSearchGroup group(String id) {
        if (id == null) return null;
        String raw = redis.opsForValue().get(PREFIX + "group:" + id);
        return raw == null ? null : JsonUtil.fromJson(raw, HotSearchGroup.class);
    }

    public HotSearchGroup named(String name) {
        HotSearchGroup group = group(redis.opsForValue().get(PREFIX + "name:" + digest(name)));
        return group != null && name.equals(group.name()) ? group : null;
    }

    public HotSearchGroup alias(String text, String version) {
        String raw = redis.opsForValue().get(aliasKey(text));
        if (raw == null) return null;
        Alias alias = JsonUtil.fromJson(raw, Alias.class);
        return version.equals(alias.version()) ? group(alias.groupId()) : null;
    }

    public Map<String, String> aliases(List<String> texts, String version) {
        if (texts.isEmpty()) return Map.of();
        var batches = new ArrayList<>(byPartition(texts, text -> partition(digest(text))).values());
        var results = pipeline(operations -> batches.forEach(batch ->
                operations.opsForValue().multiGet(batch.stream().map(this::aliasKey).toList())));
        Map<String, String> result = new LinkedHashMap<>();
        for (int b = 0; b < batches.size(); b++) {
            var batch = batches.get(b);
            var values = (List<?>) results.get(b);
            if (values == null || values.size() != batch.size()) throw new IllegalStateException("Hot search aliases unavailable");
            for (int i = 0; i < batch.size(); i++) {
                if (values.get(i) == null) continue;
                var alias = JsonUtil.fromJson((String) values.get(i), Alias.class);
                if (version.equals(alias.version())) result.put(batch.get(i), alias.groupId());
            }
        }
        return result;
    }

    public void remember(String text, HotSearchGroup group) {
        String digest = digest(text);
        int partition = partition(digest);
        redis.execute(ALIAS, List.of(aliasKey(text), "{hs:a:" + partition + "}:index"),
                JsonUtil.toJson(new Alias(group.id(), group.version())), String.valueOf(System.currentTimeMillis()),
                String.valueOf(properties.getAliasTtlSeconds() * 1000L), String.valueOf(quota(properties.getPendingLimit(), partition)), digest);
    }

    int partition(String token) { return Math.floorMod(token.hashCode(), partitionCount); }
    String pendingKey(int partition) { return "{hs:p:" + partition + "}:pending"; }
    String assignmentKey(Event event) { return "{hs:p:" + partition(event.token()) + "}:assignment:" + event.token(); }
    String aliasKey(String text) {
        String digest = digest(text);
        return "{hs:a:" + partition(digest) + "}:alias:" + digest;
    }

    private int quota(int total, int partition) {
        return total / partitionCount + (partition < total % partitionCount ? 1 : 0);
    }

    private <T> Map<Integer, List<T>> byPartition(Collection<T> items, ToIntFunction<T> route) {
        Map<Integer, List<T>> result = new LinkedHashMap<>();
        items.forEach(item -> result.computeIfAbsent(route.applyAsInt(item), ignored -> new ArrayList<>()).add(item));
        return result;
    }

    public List<HotSearchGroup> groups(long now) {
        var results = pipeline(operations -> {
            operations.opsForZSet().removeRangeByScore(PREFIX + "active", Double.NEGATIVE_INFINITY, now - WINDOW_MILLIS);
            operations.opsForZSet().range(PREFIX + "active", 0, properties.getGroupLimit() - 1);
        });
        var ids = (Collection<?>) results.get(1);
        if (ids == null || ids.isEmpty()) return List.of();
        List<String> values = redis.opsForValue().multiGet(ids.stream().map(id -> PREFIX + "group:" + id).toList());
        if (values == null) return List.of();
        return values.stream().filter(Objects::nonNull).map(value -> JsonUtil.fromJson(value, HotSearchGroup.class)).toList();
    }

    public void retire(HotSearchGroup group) {
        retire(List.of(group));
    }

    public void retire(Collection<HotSearchGroup> groups) {
        if (groups.isEmpty()) return;
        List<String> keys = new ArrayList<>();
        groups.forEach(group -> { keys.add(PREFIX + "group:" + group.id()); keys.add(PREFIX + "users:" + group.id()); });
        pipeline(operations -> {
            operations.opsForZSet().remove(PREFIX + "active", groups.stream().map(HotSearchGroup::id).toArray());
            operations.delete(keys);
        });
    }

    public boolean contribute(HotSearchGroup group, Event event, long now) {
        return Long.valueOf(1).equals(redis.execute(CONTRIBUTE, contributionKeys(group),
                contributionArgs(group, event, now, JsonUtil.toJson(group))));
    }

    public boolean contribute(HotSearchGroup group, List<Event> events, long now) {
        if (events.isEmpty()) return false;
        String payload = JsonUtil.toJson(group);
        var keys = contributionKeys(group);
        var results = pipeline(operations -> events.forEach(event -> operations.execute(CONTRIBUTE, keys,
                contributionArgs(group, event, now, payload))));
        return results.stream().anyMatch(Long.valueOf(1)::equals);
    }

    private List<String> contributionKeys(HotSearchGroup group) {
        return List.of(PREFIX + "users:" + group.id(), PREFIX + "group:" + group.id(), PREFIX + "active",
                PREFIX + "name:" + digest(group.name()));
    }

    private String[] contributionArgs(HotSearchGroup group, Event event, long now, String payload) {
        String member = Math.floorDiv(event.happenedAt(), Duration.ofHours(1).toMillis()) + ":" + event.userId();
        return new String[]{String.valueOf(now), String.valueOf(WINDOW_MILLIS), String.valueOf(event.happenedAt()), member,
                payload, group.id(), String.valueOf(properties.getGroupLimit()), String.valueOf(properties.getContributionLimit())};
    }

    public Ranking ranking(HotSearchGroup group, long now) {
        return rankings(List.of(group), now).get(0);
    }

    public List<Ranking> rankings(Collection<HotSearchGroup> groups, long now) {
        var all = new ArrayList<>(groups);
        List<Ranking> rankings = new ArrayList<>();
        for (int from = 0; from < all.size(); from += RANK_BATCH_SIZE) {
            var chunk = all.subList(from, Math.min(from + RANK_BATCH_SIZE, all.size()));
            var results = pipeline(operations -> chunk.forEach(group -> {
                String key = PREFIX + "users:" + group.id();
                operations.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, now - WINDOW_MILLIS);
                operations.opsForZSet().rangeByScore(key, now - WINDOW_MILLIS + 1, now);
            }));
            for (int i = 0; i < chunk.size(); i++) {
                var members = (Collection<?>) results.get(i * 2 + 1);
                if (members == null) members = List.of();
                long users = members.stream().map(value -> {
                    String member = (String) value;
                    return member.substring(member.indexOf(':') + 1);
                }).distinct().count();
                rankings.add(new Ranking(chunk.get(i), members.size(), Math.toIntExact(users)));
            }
        }
        return rankings;
    }

    @SuppressWarnings("unchecked")
    private List<Object> pipeline(Consumer<RedisOperations<String, String>> commands) {
        return redis.executePipelined(new SessionCallback<>() {
            @Override public <K, V> Object execute(RedisOperations<K, V> operations) {
                commands.accept((RedisOperations<String, String>) operations);
                return null;
            }
        });
    }

    public void publish(List<String> names) {
        redis.opsForValue().set(PREFIX + "snapshot", JsonUtil.toJson(new Snapshot(names)),
                Duration.ofSeconds(properties.getSnapshotTtlSeconds()));
    }

    public List<String> hot() {
        String raw = redis.opsForValue().get(PREFIX + "snapshot");
        return raw == null ? List.of() : JsonUtil.fromJson(raw, Snapshot.class).names();
    }
}
