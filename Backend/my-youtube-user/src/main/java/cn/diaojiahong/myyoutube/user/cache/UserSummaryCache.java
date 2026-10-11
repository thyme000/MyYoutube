package cn.diaojiahong.myyoutube.user.cache;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 内部契约(UserPort)里的用户摘要缓存: 跨服务读同一批人时削掉重复的库查询。
 * 放 provider 侧, 调用方(13 处)无需改动; 资料变更由本服务写入点精准失效。
 * 用 Hash 逐字段存而非 JSON: 契约 DTO 带 LocalDateTime, Gson 无对应适配器。
 */
@Service
@RequiredArgsConstructor
public class UserSummaryCache {

    /** 资料字段极少变, 计数本就随缓冲滞后; 固定 TTL 不续期, 只为削峰 */
    private static final long TTL_SECONDS = 60L;
    /** 查无此人的负缓存标记, 避免反复回源 */
    private static final String F_NOT_FOUND = "notFound";
    private static final String F_ID = "id";
    private static final String F_USERNAME = "username";
    private static final String F_NICKNAME = "nickname";
    private static final String F_AVATAR = "avatarUrl";
    private static final String F_INTRODUCTION = "introduction";
    private static final String F_FANS = "fans";
    private static final String F_ATTENTION = "attentionCount";
    private static final String F_LIKE = "likeCount";
    private static final String F_CONTENT = "contentCount";
    private static final String F_CREATE_TIME = "createTime";

    private final StringRedisTemplate redis;

    public UserSummary get(Long id, Function<Long, UserSummary> loader) {
        if (id == null) {
            return null;
        }
        Map<String, String> fields = read(id);
        if (fields != null) {
            return fields.containsKey(F_NOT_FOUND) ? null : toSummary(fields);
        }
        UserSummary loaded = loader.apply(id);
        write(List.of(id), loaded == null ? Map.of() : Map.of(id, loaded));
        return loaded;
    }

    public List<UserSummary> listByIds(Collection<Long> ids, Function<Collection<Long>, List<UserSummary>> loader) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        Map<Long, Map<String, String>> cached = readAll(distinct);
        List<Long> misses = distinct.stream().filter(id -> !cached.containsKey(id)).toList();
        if (!misses.isEmpty()) {
            Map<Long, UserSummary> loaded = loader.apply(misses).stream()
                    .filter(s -> s.getId() != null)
                    .collect(Collectors.toMap(UserSummary::getId, s -> s, (a, b) -> a));
            write(misses, loaded);
            loaded.forEach((id, s) -> cached.put(id, toFields(s)));
        }
        List<UserSummary> result = new ArrayList<>();
        for (Long id : distinct) {
            Map<String, String> fields = cached.get(id);
            if (fields != null && !fields.containsKey(F_NOT_FOUND)) {
                result.add(toSummary(fields));
            }
        }
        return result;
    }

    /** 昵称/头像等资料变更后调用, 下次读取回源 */
    public void evict(Long userId) {
        if (userId != null) {
            redis.delete(RedisConstants.USER_SUMMARY + userId);
        }
    }

    /** 返回 null 表示 key 不存在(未缓存); 返回含负缓存标记的 map 表示查无此人 */
    private Map<String, String> read(Long id) {
        Map<Object, Object> raw = redis.opsForHash().entries(RedisConstants.USER_SUMMARY + id);
        return raw.isEmpty() ? null : toStringMap(raw);
    }

    private Map<Long, Map<String, String>> readAll(List<Long> ids) {
        List<Object> results = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (Long id : ids) {
                connection.hashCommands().hGetAll(ser().serialize(RedisConstants.USER_SUMMARY + id));
            }
            return null;
        });
        Map<Long, Map<String, String>> map = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            @SuppressWarnings("unchecked")
            Map<Object, Object> raw = (Map<Object, Object>) results.get(i);
            if (raw != null && !raw.isEmpty()) {
                map.put(ids.get(i), toStringMap(raw));
            }
        }
        return map;
    }

    /** 回源结果落缓存; loaded 里没有的 id 记负缓存 */
    private void write(Collection<Long> ids, Map<Long, UserSummary> loaded) {
        StringRedisSerializer ser = ser();
        redis.executePipelined((RedisCallback<Object>) connection -> {
            for (Long id : ids) {
                byte[] key = ser.serialize(RedisConstants.USER_SUMMARY + id);
                UserSummary summary = loaded.get(id);
                Map<byte[], byte[]> fields = new LinkedHashMap<>();
                if (summary == null) {
                    fields.put(ser.serialize(F_NOT_FOUND), ser.serialize("1"));
                } else {
                    toFields(summary).forEach((k, v) -> fields.put(ser.serialize(k), ser.serialize(v)));
                }
                connection.hashCommands().hMSet(key, fields);
                connection.keyCommands().expire(key, TTL_SECONDS);
            }
            return null;
        });
    }

    private Map<String, String> toFields(UserSummary s) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(F_ID, String.valueOf(s.getId()));
        fields.put(F_USERNAME, blankIfNull(s.getUsername()));
        fields.put(F_NICKNAME, blankIfNull(s.getNickname()));
        fields.put(F_AVATAR, blankIfNull(s.getAvatarUrl()));
        fields.put(F_INTRODUCTION, blankIfNull(s.getIntroduction()));
        fields.put(F_FANS, String.valueOf(s.getFans() == null ? 0 : s.getFans()));
        fields.put(F_ATTENTION, String.valueOf(s.getAttentionCount() == null ? 0 : s.getAttentionCount()));
        fields.put(F_LIKE, String.valueOf(s.getLikeCount() == null ? 0 : s.getLikeCount()));
        fields.put(F_CONTENT, String.valueOf(s.getContentCount() == null ? 0 : s.getContentCount()));
        fields.put(F_CREATE_TIME, s.getCreateTime() == null ? ""
                : String.valueOf(s.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()));
        return fields;
    }

    private static UserSummary toSummary(Map<String, String> f) {
        UserSummary s = new UserSummary();
        s.setId(Long.valueOf(f.get(F_ID)));
        s.setUsername(blankToNull(f.get(F_USERNAME)));
        s.setNickname(blankToNull(f.get(F_NICKNAME)));
        s.setAvatarUrl(blankToNull(f.get(F_AVATAR)));
        s.setIntroduction(blankToNull(f.get(F_INTRODUCTION)));
        s.setFans(Long.valueOf(f.getOrDefault(F_FANS, "0")));
        s.setAttentionCount(Long.valueOf(f.getOrDefault(F_ATTENTION, "0")));
        s.setLikeCount(Long.valueOf(f.getOrDefault(F_LIKE, "0")));
        s.setContentCount(Long.valueOf(f.getOrDefault(F_CONTENT, "0")));
        String createTime = f.get(F_CREATE_TIME);
        s.setCreateTime(createTime == null || createTime.isEmpty() ? null
                : LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(createTime)), ZoneId.systemDefault()));
        return s;
    }

    private static Map<String, String> toStringMap(Map<Object, Object> raw) {
        Map<String, String> converted = new LinkedHashMap<>();
        raw.forEach((k, v) -> converted.put(String.valueOf(k), String.valueOf(v)));
        return converted;
    }

    private static String blankIfNull(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static StringRedisSerializer ser() {
        return new StringRedisSerializer();
    }
}
