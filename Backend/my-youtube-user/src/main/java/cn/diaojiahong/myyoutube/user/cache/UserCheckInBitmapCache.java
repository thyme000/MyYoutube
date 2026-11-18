package cn.diaojiahong.myyoutube.user.cache;

import cn.diaojiahong.myyoutube.common.utils.BusinessTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserCheckInBitmapCache {
    // 更新失败留下的旧位图会过期，下次查热力图时从签到明细重建。
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final RedisScript<Long> MARK_IF_CACHED =
            RedisScript.of(new ClassPathResource("lua/check-in-mark-if-cached.lua"), Long.class);

    private final StringRedisTemplate redis;

    public void record(Long userId, LocalDate date, Supplier<List<LocalDate>> loadDates) {
        LocalDate yearStart = date.withDayOfYear(1);
        String key = key(userId, yearStart.getYear());
        try {
            if (mark(key, date)) {
                return;
            }

            if (!initialize(key, yearStart, loadDates.get())) {
                // 并发热力图查询可能先填入旧快照，补上本次已提交的签到。
                mark(key, date);
            }
        } catch (DataAccessException e) {
            log.warn("更新签到位图失败 userId={} date={}", userId, date, e);
        }
    }

    public List<LocalDate> dates(Long userId, LocalDate yearStart, Supplier<List<LocalDate>> loadDates) {
        String key = key(userId, yearStart.getYear());
        byte[] bitmap;
        try {
            bitmap = redis.execute((RedisCallback<byte[]>) connection ->
                    connection.stringCommands().get(key.getBytes(StandardCharsets.UTF_8)));
        } catch (DataAccessException e) {
            log.warn("读取签到位图失败 userId={} year={}", userId, yearStart.getYear(), e);
            return loadDates.get();
        }
        if (bitmap != null) {
            return decode(yearStart, bitmap);
        }

        List<LocalDate> dates = loadDates.get();
        try {
            initialize(key, yearStart, dates);
        } catch (DataAccessException e) {
            log.warn("重建签到位图失败 userId={} year={}", userId, yearStart.getYear(), e);
        }
        return dates;
    }

    private boolean mark(String key, LocalDate date) {
        String offset = String.valueOf(date.getDayOfYear() - 1);
        Long updated = redis.execute(MARK_IF_CACHED, List.of(key), offset);
        return Long.valueOf(1).equals(updated);
    }

    private boolean initialize(String key, LocalDate yearStart, List<LocalDate> dates) {
        long untilNextYear = Duration.between(BusinessTime.now(), yearStart.plusYears(1).atStartOfDay()).toMillis();
        long ttlMillis = Math.min(CACHE_TTL.toMillis(), untilNextYear);
        if (ttlMillis <= 0) {
            return false;
        }

        byte[] bitmap = new byte[(yearStart.lengthOfYear() + 7) / 8];
        for (LocalDate date : dates) {
            int offset = date.getDayOfYear() - 1;
            bitmap[offset / 8] |= (byte) (1 << (7 - offset % 8));
        }
        Boolean stored = redis.execute((RedisCallback<Boolean>) connection -> connection.stringCommands()
                .set(key.getBytes(StandardCharsets.UTF_8), bitmap, Expiration.milliseconds(ttlMillis),
                        SetOption.ifAbsent()));
        return Boolean.TRUE.equals(stored);
    }

    private static List<LocalDate> decode(LocalDate yearStart, byte[] bitmap) {
        List<LocalDate> dates = new ArrayList<>();
        int dayCount = Math.min(yearStart.lengthOfYear(), bitmap.length * 8);
        for (int offset = 0; offset < dayCount; offset++) {
            int mask = 1 << (7 - offset % 8);
            if ((bitmap[offset / 8] & mask) != 0) {
                dates.add(yearStart.plusDays(offset));
            }
        }
        return dates;
    }

    private static String key(Long userId, int year) {
        return "user:check-in:bitmap:" + userId + ":" + year;
    }
}
