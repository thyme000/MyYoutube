package cn.diaojiahong.myyoutube.search.hot;

import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Component
@RequiredArgsConstructor
public class HotSearchStore {
    static final String PREFIX = "{hot-search}:";
    static final long WINDOW_MILLIS = Duration.ofHours(24).toMillis();
    private static final RedisScript<Long> RECORD = RedisScript.of(new ClassPathResource("lua/hot-search-record.lua"), Long.class);
    private static final RedisScript<Long> CONTRIBUTE = RedisScript.of(new ClassPathResource("lua/hot-search-contribute.lua"), Long.class);
    private static final RedisScript<Long> ALIAS = RedisScript.of(new ClassPathResource("lua/hot-search-alias.lua"), Long.class);
    private final StringRedisTemplate redis;
    private final HotSearchProperties properties;

    public record Event(String token, String keyword, long userId, long happenedAt) {}
    private record Alias(String groupId, String version) {}
    public record Ranking(HotSearchGroup group, int score, int users) {}
    private record Snapshot(List<String> names) {}

    static String digest(String text) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    public boolean record(Event event) {
        return Long.valueOf(1).equals(redis.execute(RECORD, List.of(PREFIX + "pending"),
                JsonUtil.toJson(event), String.valueOf(event.happenedAt()), String.valueOf(WINDOW_MILLIS),
                String.valueOf(properties.getPendingLimit())));
    }

    public List<Event> pending(long now) {
        redis.opsForZSet().removeRangeByScore(PREFIX + "pending", Double.NEGATIVE_INFINITY, now - WINDOW_MILLIS);
        Set<String> raw = redis.opsForZSet().range(PREFIX + "pending", 0, properties.getBatchSize() - 1);
        return raw == null ? List.of() : raw.stream().map(value -> JsonUtil.fromJson(value, Event.class)).toList();
    }

    public void remove(Event event) {
        redis.opsForZSet().remove(PREFIX + "pending", JsonUtil.toJson(event));
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

    public void remember(String text, HotSearchGroup group) {
        redis.execute(ALIAS, List.of(PREFIX + "alias:" + digest(text), PREFIX + "aliases"),
                JsonUtil.toJson(new Alias(group.id(), group.version())), String.valueOf(System.currentTimeMillis()),
                String.valueOf(properties.getAliasTtlSeconds() * 1000L), String.valueOf(properties.getPendingLimit()), digest(text));
    }

    public List<HotSearchGroup> groups(long now) {
        redis.opsForZSet().removeRangeByScore(PREFIX + "active", Double.NEGATIVE_INFINITY, now - WINDOW_MILLIS);
        Set<String> ids = redis.opsForZSet().range(PREFIX + "active", 0, properties.getGroupLimit() - 1);
        if (ids == null || ids.isEmpty()) return List.of();
        List<String> values = redis.opsForValue().multiGet(ids.stream().map(id -> PREFIX + "group:" + id).toList());
        if (values == null) return List.of();
        return values.stream().filter(Objects::nonNull).map(value -> JsonUtil.fromJson(value, HotSearchGroup.class)).toList();
    }

    public void retire(HotSearchGroup group) {
        redis.opsForZSet().remove(PREFIX + "active", group.id());
        redis.delete(List.of(PREFIX + "group:" + group.id(), PREFIX + "users:" + group.id()));
    }

    public boolean contribute(HotSearchGroup group, Event event, long now) {
        String member = Math.floorDiv(event.happenedAt(), Duration.ofHours(1).toMillis()) + ":" + event.userId();
        return Long.valueOf(1).equals(redis.execute(CONTRIBUTE,
                List.of(PREFIX + "users:" + group.id(), PREFIX + "group:" + group.id(), PREFIX + "active",
                        PREFIX + "name:" + digest(group.name())),
                String.valueOf(now), String.valueOf(WINDOW_MILLIS), String.valueOf(event.happenedAt()), member,
                JsonUtil.toJson(group), group.id(), String.valueOf(properties.getGroupLimit()),
                String.valueOf(properties.getContributionLimit())));
    }

    public Ranking ranking(HotSearchGroup group, long now) {
        String key = PREFIX + "users:" + group.id();
        redis.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, now - WINDOW_MILLIS);
        Set<String> members = redis.opsForZSet().rangeByScore(key, now - WINDOW_MILLIS + 1, now);
        if (members == null) return new Ranking(group, 0, 0);
        long users = members.stream().map(value -> value.substring(value.indexOf(':') + 1)).distinct().count();
        return new Ranking(group, members.size(), Math.toIntExact(users));
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
