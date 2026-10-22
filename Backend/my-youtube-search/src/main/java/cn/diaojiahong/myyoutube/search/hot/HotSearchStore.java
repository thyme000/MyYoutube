package cn.diaojiahong.myyoutube.search.hot;

import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import lombok.RequiredArgsConstructor;
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
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
public class HotSearchStore {
    static final String PREFIX = "{hot-search}:";
    static final long WINDOW_MILLIS = Duration.ofHours(24).toMillis();
    static final long PENDING_MILLIS = Duration.ofDays(3).toMillis();
    static final String PENDING_KEY = PREFIX + "pending:v2";
    private static final ZoneId STAT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int RANK_BATCH_SIZE = 32;
    private static final int ACK_BATCH_SIZE = 200;
    private static final RedisScript<Long> RECORD = RedisScript.of(new ClassPathResource("lua/hot-search-record.lua"), Long.class);
    private static final RedisScript<Long> CONTRIBUTE = RedisScript.of(new ClassPathResource("lua/hot-search-contribute.lua"), Long.class);
    private static final RedisScript<Long> ALIAS = RedisScript.of(new ClassPathResource("lua/hot-search-alias.lua"), Long.class);
    private static final RedisScript<Long> ACK = RedisScript.of(new ClassPathResource("lua/hot-search-ack.lua"), Long.class);
    private final StringRedisTemplate redis;
    private final HotSearchProperties properties;

    public record Event(String token, String keyword, String rawKeyword, long userId, long happenedAt) {}
    public record Assignment(String groupId, String groupName, String version, String statDate) {}
    private record Alias(String groupId, String version) {}
    public record Ranking(HotSearchGroup group, int score, int users) {}
    private record Snapshot(List<String> names) {}

    static String digest(String text) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    public boolean record(Event event) {
        return Long.valueOf(1).equals(redis.execute(RECORD, List.of(PENDING_KEY),
                JsonUtil.toJson(event), String.valueOf(event.happenedAt()), String.valueOf(PENDING_MILLIS),
                String.valueOf(properties.getPendingLimit())));
    }

    public List<Event> pending(long now) {
        var results = pipeline(operations -> {
            operations.opsForZSet().removeRangeByScore(PENDING_KEY, Double.NEGATIVE_INFINITY, now - PENDING_MILLIS);
            operations.opsForZSet().range(PENDING_KEY, 0, properties.getBatchSize() - 1);
        });
        var raw = (Collection<?>) results.get(1);
        return raw == null ? List.of() : raw.stream().map(value -> JsonUtil.fromJson((String) value, Event.class)).toList();
    }

    public void remove(Event event) {
        remove(List.of(event));
    }

    public void remove(Collection<Event> events) {
        var batch = new ArrayList<>(events);
        for (int from = 0; from < batch.size(); from += ACK_BATCH_SIZE) {
            var chunk = batch.subList(from, Math.min(from + ACK_BATCH_SIZE, batch.size()));
            List<String> keys = new ArrayList<>();
            keys.add(PENDING_KEY);
            chunk.forEach(event -> keys.add(PREFIX + "assignment:" + event.token()));
            redis.execute(ACK, keys, chunk.stream().map(JsonUtil::toJson).toArray(String[]::new));
        }
    }

    public Assignment assignment(Event event) {
        return assignments(List.of(event)).get(event);
    }

    public Map<Event, Assignment> assignments(List<Event> events) {
        if (events.isEmpty()) return Map.of();
        var values = redis.opsForValue().multiGet(events.stream().map(event -> PREFIX + "assignment:" + event.token()).toList());
        if (values == null || values.size() != events.size()) throw new IllegalStateException("Hot search assignments unavailable");
        Map<Event, Assignment> result = new LinkedHashMap<>();
        for (int i = 0; i < events.size(); i++) {
            if (values.get(i) != null) result.put(events.get(i), JsonUtil.fromJson(values.get(i), Assignment.class));
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
                PREFIX + "assignment:" + event.token(), JsonUtil.toJson(assignment),
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
        String raw = redis.opsForValue().get(PREFIX + "alias:" + digest(text));
        if (raw == null) return null;
        Alias alias = JsonUtil.fromJson(raw, Alias.class);
        return version.equals(alias.version()) ? group(alias.groupId()) : null;
    }

    public Map<String, String> aliases(List<String> texts, String version) {
        if (texts.isEmpty()) return Map.of();
        var values = redis.opsForValue().multiGet(texts.stream().map(text -> PREFIX + "alias:" + digest(text)).toList());
        if (values == null || values.size() != texts.size()) throw new IllegalStateException("Hot search aliases unavailable");
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < texts.size(); i++) {
            if (values.get(i) == null) continue;
            var alias = JsonUtil.fromJson(values.get(i), Alias.class);
            if (version.equals(alias.version())) result.put(texts.get(i), alias.groupId());
        }
        return result;
    }

    public void remember(String text, HotSearchGroup group) {
        redis.execute(ALIAS, List.of(PREFIX + "alias:" + digest(text), PREFIX + "aliases"),
                JsonUtil.toJson(new Alias(group.id(), group.version())), String.valueOf(System.currentTimeMillis()),
                String.valueOf(properties.getAliasTtlSeconds() * 1000L), String.valueOf(properties.getPendingLimit()), digest(text));
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
