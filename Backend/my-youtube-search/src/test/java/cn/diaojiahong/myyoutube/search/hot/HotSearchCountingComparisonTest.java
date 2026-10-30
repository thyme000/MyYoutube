package cn.diaojiahong.myyoutube.search.hot;

import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisClusterNode;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

// 显式启用的计数结构对照；不替换生产 Store，也不包含归组、元信息、索引和数据库开销。
@EnabledIfSystemProperty(named = "hot.count.compare", matches = "true")
@Timeout(180)
class HotSearchCountingComparisonTest {
    static final long HOUR = Duration.ofHours(1).toMillis();
    static final long WINDOW = HotSearchStore.WINDOW_MILLIS;
    static final int BATCH_SIZE = 200;

    static final RedisScript<Long> ZSET_ADD = RedisScript.of("""
            local now = tonumber(ARGV[1])
            local at = tonumber(ARGV[2])
            if at <= now - 86400000 or at > now then return 0 end
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - 86400000)
            if not redis.call('ZSCORE', KEYS[1], ARGV[3]) and redis.call('ZCARD', KEYS[1]) >= 10000 then return 0 end
            redis.call('ZADD', KEYS[1], 'GT', at, ARGV[3])
            local latest = redis.call('ZREVRANGE', KEYS[1], 0, 0, 'WITHSCORES')
            redis.call('PEXPIREAT', KEYS[1], tonumber(latest[2]) + 86400000)
            return 1
            """, Long.class);

    static final RedisScript<Long> BUCKET_ADD = RedisScript.of("""
            local changed = redis.call(ARGV[1], KEYS[1], unpack(ARGV, 3))
            redis.call('PEXPIREAT', KEYS[1], ARGV[2])
            return changed
            """, Long.class);

    static final RedisScript<List> BUCKET_COUNT = RedisScript.of("""
            local score = 0
            for i = 1, #KEYS do score = score + redis.call(ARGV[1], KEYS[i]) end
            local users
            if ARGV[1] == 'SCARD' then users = #redis.call('SUNION', unpack(KEYS))
            else users = redis.call('PFCOUNT', unpack(KEYS)) end
            return {score, users}
            """, List.class);

    static final RedisScript<Long> MEMORY = RedisScript.of(
            "return redis.call('MEMORY', 'USAGE', KEYS[1], 'SAMPLES', 0) or 0",
            Long.class);

    static final RedisScript<Long> EXPIRES = RedisScript.of("return redis.call('PEXPIRETIME', KEYS[1])", Long.class);

    enum Kind {
        ZSET, SET, HLL
    }
    record Hit(String group, long user, long at) {
    }

    record Count(long score, long users, long returnedElements) {
    }

    record Timing(double wallMs, double redisCpuMs) {
    }

    record Result(String scenario, Kind kind, int groups, int events, int keys, long memoryBytes,
                  Timing write, Timing replay, List<Double> readMs, double readMedianMs, double readRedisCpuMsPerPass,
                  double maxScoreErrorPercent,
                  double maxUsersErrorPercent,
                  List<String> rankOrder,
                  Map<String,
                  Count> counts) {
    }

    LettuceConnectionFactory connection;
    StringRedisTemplate redis;
    long now;

    final Map<Kind, Set<String>> writtenKeys = new EnumMap<>(Kind.class);

    @BeforeEach
    void setup() {
        int port = Integer.getInteger("hot.redis.port", 0);
        assertTrue(port > 10000, "仅使用隔离测试 Redis");
        connection = HotSearchRedisTest.connection(port);
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
        // 对照只测计数核心，复用本测试线程的连接，排除每批建连与拓扑握手成本。
        RedisConnectionUtils.bindConnection(connection);
        for (HotSearchCountingComparisonTest.Kind kind : Kind.values()) writtenKeys.put(kind, new LinkedHashSet<>());
        now = System.currentTimeMillis();
        clean();
    }

    void clean() {
        Set<String> keys = redis.keys("{hs:compare:*");
        if (keys != null) keys.forEach(redis::delete);
        writtenKeys.values().forEach(Set::clear);
    }

    @AfterEach
    void close() {
        try {
            if (redis != null) clean();
        } finally {
            if (connection != null) {
                RedisConnectionUtils.unbindConnection(connection);
                connection.destroy();
            }
        }
    }

    String key(Kind kind, String group, long hour) {
        return "{hs:compare:" + group + "}:" + kind.name().toLowerCase(Locale.ROOT) + ":" + (kind == Kind.ZSET ? "users" : hour);
    }

    void write(Kind kind, List<Hit> hits, long at) {
        for (int from = 0; from < hits.size(); from += BATCH_SIZE) {
            List<HotSearchCountingComparisonTest.Hit> batch = hits.subList(
                    from,
                    Math.min(from + BATCH_SIZE, hits.size()))
                    .stream()
                    .filter(hit -> hit.at() > at - WINDOW && hit.at() <= at).toList();
            Map<String, List<Hit>> buckets = new LinkedHashMap<>();
            batch.forEach(hit -> buckets.computeIfAbsent(key(kind, hit.group(), Math.floorDiv(hit.at(), HOUR)), ignored -> new ArrayList<>()).add(hit));
            writtenKeys.get(kind).addAll(buckets.keySet());
            pipeline(operations -> buckets.forEach((key, members) -> {
                if (kind == Kind.ZSET) {
                    for (HotSearchCountingComparisonTest.Hit hit : members) operations.execute(
                            ZSET_ADD,
                            List.of(key),
                            String.valueOf(at),
                            String.valueOf(hit.at()),
                            Math.floorDiv(hit.at(), HOUR) + ":" + hit.user());
                } else {
                    List<String> args = new ArrayList<>();
                    args.add(kind == Kind.SET ? "SADD" : "PFADD");
                    args.add(String.valueOf((Math.floorDiv(members.get(0).at(), HOUR) + 1) * HOUR + WINDOW));
                    members.forEach(hit -> args.add(String.valueOf(hit.user())));
                    operations.execute(BUCKET_ADD, List.of(key), args.toArray());
                }
            }));
        }
    }

    Map<String, Count> read(Kind kind, List<String> groups, long at) {
        List<Object> raw = pipeline(operations -> groups.forEach(group -> {
            if (kind == Kind.ZSET) {
                String key = key(kind, group, 0);
                operations.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, at - WINDOW);
                operations.opsForZSet().rangeByScore(key, at - WINDOW + 1, at);
            } else {
                List<String> keys = new ArrayList<>();
                for (long hour = Math.floorDiv(at - WINDOW, HOUR); hour <= Math.floorDiv(at, HOUR); hour++) keys.add(key(kind, group, hour));
                operations.execute(BUCKET_COUNT, keys, kind == Kind.SET ? "SCARD" : "PFCOUNT");
            }
        }));
        Map<String, Count> result = new LinkedHashMap<>();
        for (int i = 0; i < groups.size(); i++) {
            if (kind == Kind.ZSET) {
                Collection<?> members = (Collection<?>) raw.get(i * 2 + 1);
                long users = members.stream()
                        .map(value -> ((String) value).substring(((String) value).indexOf(':') + 1))
                        .distinct()
                        .count();
                result.put(groups.get(i), new Count(members.size(), users, members.size()));
            } else {
                List<?> counts = (List<?>) raw.get(i);
                result.put(
                        groups.get(i),
                        new Count(((Number) counts.get(0)).longValue(), ((Number) counts.get(1)).longValue(), 2));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    List<Object> pipeline(Consumer<RedisOperations<String, String>> commands) {
        return redis.executePipelined(new SessionCallback<>() {
            @Override
            public <K, V> Object execute(RedisOperations<K, V> operations) {
                commands.accept((RedisOperations<String, String>) operations);
                return null;
            }
        });
    }

    double cpuMs() {
        double seconds = 0;
        if (System.getProperty("hot.redis.cluster.nodes") == null) {
            try (RedisConnection current = connection.getConnection()) {
                Properties info = current.serverCommands().info("cpu");
                seconds = Double.parseDouble(info.getProperty("used_cpu_user")) + Double.parseDouble(info.getProperty("used_cpu_sys"));
            }
        } else {
            try (RedisClusterConnection cluster = connection.getClusterConnection()) {
                for (RedisClusterNode node : cluster.clusterGetNodes()) {
                    if (!node.isMaster()) continue;
                    Properties info = cluster.info(node, "cpu");
                    seconds += Double.parseDouble(info.getProperty("used_cpu_user")) + Double.parseDouble(info.getProperty("used_cpu_sys"));
                }
            }
        }
        return seconds * 1000;
    }

    Timing timedWrite(Kind kind, List<Hit> hits) {
        double cpu = cpuMs();
        long started = System.nanoTime();
        write(kind, hits, now);
        double wallMs = (System.nanoTime() - started) / 1_000_000d;
        return new Timing(wallMs, cpuMs() - cpu);
    }

    List<Hit> data(String scenario) {
        List<Hit> hits = new ArrayList<>();
        for (int group = 0; group < 8; group++) {
            int hours = scenario.equals("hot-near-ties") ? 1 : 24;
            int users = scenario.equals("low") ? 12 : scenario.equals("spread") ? 320 : 8000 - group * 10;
            int userRange = scenario.equals("low") ? 24 : scenario.equals("spread") ? 512 : 8000;
            for (int hour = 0; hour < hours; hour++) {
                long time = Math.floorDiv(now, HOUR) * HOUR - (hours - hour - 1L) * HOUR;
                time += Math.min(600_000, (now - Math.floorDiv(now, HOUR) * HOUR) / 2);
                for (int user = 0; user < users; user++) {
                    var hit = new Hit(
                            "g" + group,
                            2_000_000_000_000_000_000L + group * 1_000_000L + (hour * 137L + user) % userRange,
                            time);
                    hits.add(hit);
                    hits.add(hit);
                }
            }
        }
        return hits;
    }

    Map<String, Count> truth(List<Hit> hits, List<String> groups) {
        Map<String, Count> result = new LinkedHashMap<>();
        for (String group : groups) {
            List<HotSearchCountingComparisonTest.Hit> valid = hits.stream()
                    .filter(hit -> hit.group().equals(group) && hit.at() > now - WINDOW && hit.at() <= now)
                    .toList();
            long score = valid.stream().map(hit -> Math.floorDiv(hit.at(), HOUR) + ":" + hit.user()).distinct().count();
            result.put(group, new Count(score, valid.stream().map(Hit::user).distinct().count(), score));
        }
        return result;
    }

    List<String> order(Map<String, Count> counts) {
        return counts.keySet()
                .stream()
                .sorted(Comparator.<String>comparingLong(group -> counts.get(group).score()).reversed()
                .thenComparing(group -> group)).toList();
    }

    double maxError(Map<String, Count> counts, Map<String, Count> truth, boolean users) {
        return counts.keySet().stream().mapToDouble(group -> {
            long actual = users ? counts.get(group).users() : counts.get(group).score();
            long expected = users ? truth.get(group).users() : truth.get(group).score();
            return Math.abs(actual - expected) * 100d / expected;
        }).max().orElse(0);
    }

    @Test
    void comparesCountingCostsWithoutChangingProduction() throws Exception {
        List<Result> results = new ArrayList<>();
        List<String> groups = IntStream.range(0, 8).mapToObj(i -> "g" + i).toList();
        // 小样例预热三种实现；各场景轮换执行顺序，保留原始读耗时而不断言性能。
        List<HotSearchCountingComparisonTest.Hit> warm = List.of(new Hit("warm", 1, now));
        for (HotSearchCountingComparisonTest.Kind kind : Kind.values()) {
            write(kind, warm, now);
            read(kind, List.of("warm"), now);
        }
        int scenarioIndex = 0;
        for (String scenario : List.of("low", "spread", "hot-near-ties")) {
            clean();
            List<HotSearchCountingComparisonTest.Hit> hits = data(scenario);
            Map<String,HotSearchCountingComparisonTest.Count> truth = truth(hits, groups);
            for (int offset = 0; offset < Kind.values().length; offset++) {
                HotSearchCountingComparisonTest.Kind kind = Kind.values()[(scenarioIndex + offset) % Kind.values().length];
                HotSearchCountingComparisonTest.Timing write = timedWrite(kind, hits);
                Map<String,HotSearchCountingComparisonTest.Count> before = read(kind, groups, now);
                HotSearchCountingComparisonTest.Timing replay = timedWrite(kind, hits);
                assertEquals(before, read(kind, groups, now), "重复搬运不能改变同用户小时贡献");
                if (kind != Kind.HLL) {
                    for (String group : groups) {
                        assertEquals(truth.get(group).score(), before.get(group).score());
                        assertEquals(truth.get(group).users(), before.get(group).users());
                    }
                }
                for (int i = 0; i < 3; i++) read(kind, groups, now);
                double cpu = cpuMs();
                List<Double> samples = new ArrayList<>();
                for (int i = 0; i < 7; i++) {
                    long started = System.nanoTime();
                    read(kind, groups, now);
                    samples.add((System.nanoTime() - started) / 1_000_000d);
                }
                double readCpu = (cpuMs() - cpu) / samples.size();
                Map<String,HotSearchCountingComparisonTest.Count> counts = read(kind, groups, now);
                List<Object> sizes = pipeline(operations -> writtenKeys.get(kind).forEach(key -> operations.execute(
                        MEMORY,
                        List.of(key))));
                long memory = sizes.stream().mapToLong(value -> (Long) value).sum();
                int keys = (int) sizes.stream().filter(value -> (Long) value > 0).count();
                List<Double> sorted = samples.stream().sorted().toList();
                var result = new Result(scenario, kind, groups.size(), hits.size(), keys, memory, write, replay,
                        samples,
                        sorted.get(3),
                        readCpu,
                        maxError(counts, truth, false),
                        maxError(counts, truth, true),
                        order(counts),
                        counts);
                results.add(result);
                System.out.printf(
                        Locale.ROOT,
                        "Counting comparison case=%s kind=%s keys=%d memoryBytes=%d writeMs=%.2f readMedianMs=%.3f scoreErrorPct=%.3f usersErrorPct=%.3f%n",
                        scenario,
                        kind,
                        keys,
                        memory,
                        write.wallMs(),
                        result.readMedianMs(),
                        result.maxScoreErrorPercent(),
                        result.maxUsersErrorPercent());
            }
            scenarioIndex++;
        }
        Path output = Path.of(System.getProperty("basedir", "."), "target/hot-counting-comparison.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, JsonUtil.toJson(results));
        System.out.println("Counting comparison output=" + output.toAbsolutePath());
    }

    @Test
    void bucketBoundariesAreSeparateFromHllApproximation() {
        long oldestHour = Math.floorDiv(now - WINDOW, HOUR) * HOUR;
        List<HotSearchCountingComparisonTest.Hit> hits = List.of(
                new Hit("boundary", 1001, oldestHour),
                new Hit("boundary", 1002, oldestHour + HOUR - 1));
        for (HotSearchCountingComparisonTest.Kind kind : Kind.values()) {
            write(kind, hits, now - HOUR);
            HotSearchCountingComparisonTest.Count count = read(kind, List.of("boundary"), now).get("boundary");
            assertEquals(kind == Kind.ZSET ? 1 : 2, count.score());
            assertEquals(kind == Kind.ZSET ? 1 : 2, count.users());
        }
    }

    @Test
    void bucketsPreserveUserHourDeduplicationAndFixedExpiryOnReplay() {
        long previousHour = Math.floorDiv(now, HOUR) * HOUR - HOUR;
        List<HotSearchCountingComparisonTest.Hit> hits = List.of(
                new Hit("semantics", 7, previousHour + 1),
                new Hit("semantics", 7, previousHour + 2),
                new Hit("semantics", 7, previousHour - HOUR + 1));
        for (HotSearchCountingComparisonTest.Kind kind : Kind.values()) {
            write(kind, hits, now);
            String key = key(kind, "semantics", Math.floorDiv(previousHour, HOUR));
            long expiry = redis.execute(EXPIRES, List.of(key));
            write(kind, hits, now + 1000);
            assertEquals(expiry, redis.execute(EXPIRES, List.of(key)));
            HotSearchCountingComparisonTest.Count count = read(kind, List.of("semantics"), now + 1000).get("semantics");
            assertEquals(2, count.score());
            assertEquals(1, count.users());
        }
    }
}
