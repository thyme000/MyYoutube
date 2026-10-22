package cn.diaojiahong.myyoutube.search.hot;

import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.connection.RedisZSetCommands.ZAddArgs;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

@Component
public class HotSearchStore {
    static final String PREFIX = "{hot-search}:";
    static final long WINDOW_MILLIS = Duration.ofHours(24).toMillis();
    static final long HOUR_MILLIS = Duration.ofHours(1).toMillis();
    static final long PENDING_MILLIS = Duration.ofDays(3).toMillis();
    static final ZoneId STAT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int RANK_BATCH_SIZE = 32;
    private static final int GROUP_BATCH_SIZE = 200;
    private static final int CONTRIBUTION_BATCH_SIZE = 200;
    private static final int ACK_BATCH_SIZE = 200;
    private static final RedisScript<Long> RECORD = RedisScript.of(new ClassPathResource("lua/hot-search-record.lua"), Long.class);
    private static final RedisScript<Long> CONTRIBUTE = RedisScript.of(new ClassPathResource("lua/hot-search-contribute.lua"), Long.class);
    private static final RedisScript<List> COUNT = RedisScript.of(new ClassPathResource("lua/hot-search-count.lua"), List.class);
    private static final RedisScript<Long> ALIAS = RedisScript.of(new ClassPathResource("lua/hot-search-alias.lua"), Long.class);
    private static final RedisScript<Long> ACK = RedisScript.of(new ClassPathResource("lua/hot-search-ack.lua"), Long.class);
    private static final RedisScript<Long> CLOSE_DAY = RedisScript.of(new ClassPathResource("lua/hot-search-close-day.lua"), Long.class);
    private static final RedisScript<List> STATS = RedisScript.of(new ClassPathResource("lua/hot-search-stats.lua"), List.class);
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
    public record Ranking(HotSearchGroup group, long score, long users) {}
    public record Stats(long pending, Long oldestPendingMillis, long summaryDays,
                        LocalDate oldestSummaryDate, LocalDate closedThrough, long snapshotTtlMillis) {}
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
        return pending(now, Long.MAX_VALUE);
    }

    public List<Event> pending(long now, long beforeExclusive) {
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
                    double max = beforeExclusive == Long.MAX_VALUE ? Double.POSITIVE_INFINITY : beforeExclusive - 1;
                    operations.opsForZSet().rangeByScore(key, Double.NEGATIVE_INFINITY, max, cursor.offset(), limits[i]);
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

    public Stats stats(long now) {
        // 仅批量读取计数、最老一条和 TTL，指标抓取不拉取搜索明细或向量。
        var results = pipeline(operations -> {
            for (int partition = 0; partition < partitionCount; partition++) {
                String key = pendingKey(partition);
                operations.execute(STATS, List.of(key), Long.toString(now - PENDING_MILLIS + 1));
            }
            operations.opsForZSet().size(PREFIX + "archive:pending");
            operations.opsForZSet().range(PREFIX + "archive:pending", 0, 0);
            operations.opsForValue().get(PREFIX + "archive:closed-through");
            operations.getExpire(PREFIX + "snapshot", TimeUnit.MILLISECONDS);
        });
        long pending = 0;
        Long oldest = null;
        for (int partition = 0; partition < partitionCount; partition++) {
            var values = (List<?>) results.get(partition);
            pending += (Long) values.get(0);
            long time = (Long) values.get(1);
            if (time >= 0) {
                oldest = oldest == null ? time : Math.min(oldest, time);
            }
        }
        int next = partitionCount;
        long days = (Long) results.get(next++);
        var dates = (Set<String>) results.get(next++);
        String closed = (String) results.get(next++);
        return new Stats(pending, oldest, days, dates.isEmpty() ? null : LocalDate.parse(dates.iterator().next()),
                closed == null ? null : LocalDate.parse(closed), (Long) results.get(next));
    }

    public LocalDate closedThrough() {
        String date = redis.opsForValue().get(PREFIX + "archive:closed-through");
        return date == null ? null : LocalDate.parse(date);
    }

    public LocalDate initializeArchive(LocalDate baseline) {
        redis.opsForValue().setIfAbsent(PREFIX + "archive:closed-through", baseline.toString());
        LocalDate result = closedThrough();
        if (result == null) throw new IllegalStateException("Hot search archive initialization unconfirmed");
        return result;
    }

    public boolean closeDay(LocalDate date) {
        Long result = redis.execute(CLOSE_DAY, List.of(PREFIX + "archive:closed-through", PREFIX + "archive:pending"),
                date.minusDays(1).toString(), date.toString(), String.valueOf(date.toEpochDay()));
        if (result == null || result < 0) throw new IllegalStateException("Hot search archive date is not consecutive");
        return result == 1;
    }

    public LocalDate nextSummaryDate() {
        var dates = redis.opsForZSet().range(PREFIX + "archive:pending", 0, 0);
        return dates == null || dates.isEmpty() ? null : LocalDate.parse(dates.iterator().next());
    }

    public void summaryCompleted(LocalDate date) {
        redis.opsForZSet().remove(PREFIX + "archive:pending", date.toString());
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
        String raw = redis.opsForValue().get(groupKey(id));
        return raw == null ? null : JsonUtil.fromJson(raw, HotSearchGroup.class);
    }

    public HotSearchGroup named(String name) {
        HotSearchGroup group = group(redis.opsForValue().get(nameKey(name)));
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
    String groupKey(String id) { return "{hs:g:" + id + "}:meta"; }
    String hourKey(String id, long hour) { return "{hs:g:" + id + "}:hll:" + hour; }
    String nameKey(String name) { return "hs:name:" + digest(name); }

    private int quota(int total, int partition) {
        return total / partitionCount + (partition < total % partitionCount ? 1 : 0);
    }

    private <T> Map<Integer, List<T>> byPartition(Collection<T> items, ToIntFunction<T> route) {
        Map<Integer, List<T>> result = new LinkedHashMap<>();
        items.forEach(item -> result.computeIfAbsent(route.applyAsInt(item), ignored -> new ArrayList<>()).add(item));
        return result;
    }

    public List<HotSearchGroup> groups(long now) {
        redis.opsForZSet().removeRangeByScore(PREFIX + "active", Double.NEGATIVE_INFINITY, now - WINDOW_MILLIS);
        List<HotSearchGroup> groups = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (long offset = 0; ; offset += GROUP_BATCH_SIZE) {
            var ids = redis.opsForZSet().range(PREFIX + "active", offset, offset + GROUP_BATCH_SIZE - 1);
            if (ids == null || ids.isEmpty()) break;
            var values = pipeline(operations -> ids.forEach(id -> operations.opsForValue().get(groupKey(id))));
            var iterator = ids.iterator();
            for (var value : values) {
                String id = iterator.next();
                if (value == null) missing.add(id);
                else groups.add(JsonUtil.fromJson((String) value, HotSearchGroup.class));
            }
            if (ids.size() < GROUP_BATCH_SIZE) break;
        }
        // 扫描完再清理缺失项，避免删除导致分页偏移、漏读后续组。
        for (int from = 0; from < missing.size(); from += GROUP_BATCH_SIZE) {
            redis.opsForZSet().remove(PREFIX + "active", missing.subList(from, Math.min(from + GROUP_BATCH_SIZE, missing.size())).toArray());
        }
        return groups;
    }

    public void retire(HotSearchGroup group, long now) {
        retire(List.of(group), now);
    }

    public void retire(Collection<HotSearchGroup> groups, long now) {
        var all = new ArrayList<>(groups);
        for (int from = 0; from < all.size(); from += GROUP_BATCH_SIZE) {
            var chunk = all.subList(from, Math.min(from + GROUP_BATCH_SIZE, all.size()));
            pipeline(operations -> {
                operations.opsForZSet().remove(PREFIX + "active", chunk.stream().map(HotSearchGroup::id).toArray());
                for (var group : chunk) {
                    var keys = hourKeys(group.id(), now);
                    keys.add(groupKey(group.id()));
                    operations.delete(keys);
                }
            });
        }
    }

    public boolean contribute(HotSearchGroup group, Event event, long now) {
        return contribute(group, List.of(event), now);
    }

    public boolean contribute(HotSearchGroup group, List<Event> events, long now) {
        Map<Long, List<String>> byHour = new LinkedHashMap<>();
        for (var event : events) {
            if (event.happenedAt() <= now - WINDOW_MILLIS || event.happenedAt() > now) continue;
            byHour.computeIfAbsent(Math.floorDiv(event.happenedAt(), HOUR_MILLIS), ignored -> new ArrayList<>())
                    .add(String.valueOf(event.userId()));
        }
        if (byHour.isEmpty()) return false;
        String payload = JsonUtil.toJson(group);
        var results = pipeline(operations -> byHour.forEach((hour, users) -> {
            var keys = List.of(hourKey(group.id(), hour), groupKey(group.id()));
            for (int from = 0; from < users.size(); from += CONTRIBUTION_BATCH_SIZE) {
                var chunk = users.subList(from, Math.min(from + CONTRIBUTION_BATCH_SIZE, users.size()));
                List<String> args = new ArrayList<>();
                // 固定桶结束时间 + 24 小时；重放不会续期。
                args.add(String.valueOf((hour + 1) * HOUR_MILLIS + WINDOW_MILLIS));
                args.add(payload);
                args.addAll(chunk);
                operations.execute(CONTRIBUTE, keys, args.toArray());
            }
        }));
        long expiry = results.stream().mapToLong(value -> (Long) value).max().orElseThrow();
        updateIndexes(group, expiry);
        return true;
    }

    private void updateIndexes(HotSearchGroup group, long expiry) {
        byte[] groupId = group.id().getBytes(StandardCharsets.UTF_8);
        pipeline(operations -> {
            operations.execute((RedisCallback<Boolean>) connection -> connection.zSetCommands().zAdd(
                    (PREFIX + "active").getBytes(StandardCharsets.UTF_8), expiry - WINDOW_MILLIS, groupId, ZAddArgs.empty().gt()));
            // 使用绝对过期时间，旧事件重放不能延长名称索引寿命。
            operations.execute((RedisCallback<Boolean>) connection -> connection.stringCommands().set(
                    nameKey(group.name()).getBytes(StandardCharsets.UTF_8), groupId,
                    Expiration.unixTimestamp(expiry, TimeUnit.MILLISECONDS), SetOption.UPSERT));
        });
    }

    private List<String> hourKeys(String id, long now) {
        List<String> keys = new ArrayList<>();
        // 最老的重叠小时整桶参与统计，因此时间边界最多多保留不足一小时。
        for (long hour = Math.floorDiv(now - WINDOW_MILLIS, HOUR_MILLIS); hour <= Math.floorDiv(now, HOUR_MILLIS); hour++) {
            keys.add(hourKey(id, hour));
        }
        return keys;
    }

    public Ranking ranking(HotSearchGroup group, long now) {
        return rankings(List.of(group), now).get(0);
    }

    public List<Ranking> rankings(Collection<HotSearchGroup> groups, long now) {
        var all = new ArrayList<>(groups);
        List<Ranking> rankings = new ArrayList<>();
        for (int from = 0; from < all.size(); from += RANK_BATCH_SIZE) {
            var chunk = all.subList(from, Math.min(from + RANK_BATCH_SIZE, all.size()));
            var results = pipeline(operations -> chunk.forEach(group -> operations.execute(COUNT, hourKeys(group.id(), now))));
            for (int i = 0; i < chunk.size(); i++) {
                var counts = (List<?>) results.get(i);
                rankings.add(new Ranking(chunk.get(i), (Long) counts.get(0), (Long) counts.get(1)));
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
