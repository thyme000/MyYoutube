package cn.diaojiahong.myyoutube.search.hot;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.search.api.HotSearchHistoryPort.*;
import cn.diaojiahong.myyoutube.common.monitoring.BusinessOperation;
import cn.diaojiahong.myyoutube.common.monitoring.ObservedBusiness;
import cn.diaojiahong.myyoutube.search.mapper.HotSearchHistoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class HotSearchHistoryQueryService {
    private static final int PUBLIC_BATCH_SIZE = 200;
    private final HotSearchHistoryMapper mapper;
    private final HotSearchFilter filter;
    private final HotSearchProperties properties;
    private final Clock clock;

    @ObservedBusiness(BusinessOperation.HOT_SEARCH_HISTORY)
    public List<String> hot(String date) {
        LocalDate day = pastDate(date);
        if (!properties.isEnabled()) return List.of();
        int limit = properties.getTopLimit();
        List<String> names = new ArrayList<>();
        Long after = null;
        while (names.size() < limit) {
            var rows = mapper.groups(day, after, properties.getMinUsers(), PUBLIC_BATCH_SIZE);
            for (var group : rows) {
                if (filter.eligible(group.groupName()) != null && !names.contains(group.groupName())) names.add(group.groupName());
                if (names.size() == limit) break;
            }
            if (rows.size() < PUBLIC_BATCH_SIZE) break;
            after = rows.get(rows.size() - 1).rankNo();
        }
        return List.copyOf(names);
    }

    public Page<Day> dates(String cursor, Integer size) {
        int limit = pageSize(size);
        LocalDate before = empty(cursor) ? today() : pastDate(cursor);
        return page(mapper.dates(before, limit + 1), limit, row -> row.statDate().toString());
    }

    public Page<Group> groups(String date, String cursor, Integer size) {
        LocalDate day = pastDate(date);
        int limit = pageSize(size);
        return page(mapper.groups(day, positiveCursor(cursor), 0, limit + 1), limit, row -> Long.toString(row.rankNo()));
    }

    public Page<Keyword> keywords(String date, String groupId, String cursor, Integer size) {
        LocalDate day = pastDate(date);
        requireGroup(groupId);
        int limit = pageSize(size);
        return page(mapper.keywords(day, groupId, positiveCursor(cursor), limit + 1), limit, row -> Long.toString(row.keywordId()));
    }

    public Page<User> users(String date, String groupId, Long keywordId, String cursor, Integer size) {
        LocalDate day = pastDate(date);
        requireGroup(groupId);
        requirePositive(keywordId);
        int limit = pageSize(size);
        Long after = positiveCursor(cursor);
        return page(mapper.users(day, groupId, keyword(day, groupId, keywordId), after, limit + 1), limit, row -> Long.toString(row.userId()));
    }

    public Page<Event> events(String date, String groupId, Long keywordId, Long userId, String cursor, Integer size) {
        LocalDate day = pastDate(date);
        requireGroup(groupId);
        requirePositive(keywordId);
        if (userId != null) requirePositive(userId);
        int limit = pageSize(size);
        EventCursor after = eventCursor(cursor);
        return page(mapper.events(day, groupId, keyword(day, groupId, keywordId), userId,
                after == null ? null : after.at(), after == null ? null : after.id(), limit + 1), limit,
                row -> row.happenedAt() + "." + row.id());
    }

    private String keyword(LocalDate date, String groupId, long keywordId) {
        String raw = mapper.keyword(date, groupId, keywordId);
        if (raw == null) throw new BizException(ErrorCode.INVALID_ARGUMENT, "搜索词记录不存在或不属于指定日期和分组");
        return raw;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(HotSearchStore.STAT_ZONE));
    }

    private LocalDate pastDate(String value) {
        try {
            LocalDate date = LocalDate.parse(value == null ? "" : value);
            if (date.getYear() < 1000 || !date.isBefore(today())) throw new BizException(ErrorCode.INVALID_ARGUMENT, "只能查询今天以前的历史日期");
            return date;
        } catch (DateTimeParseException error) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "日期格式必须为 yyyy-MM-dd");
        }
    }

    private static int pageSize(Integer size) {
        int result = size == null ? 20 : size;
        if (result < 1 || result > 100) throw new BizException(ErrorCode.INVALID_ARGUMENT, "分页大小必须在 1 到 100 之间");
        return result;
    }

    private static void requireGroup(String groupId) {
        if (groupId == null || groupId.isBlank() || groupId.length() > 64) throw new BizException(ErrorCode.INVALID_ARGUMENT, "分组 ID 无效");
    }

    private static void requirePositive(Long value) {
        if (value == null || value <= 0) throw new BizException(ErrorCode.INVALID_ARGUMENT, "记录或用户 ID 必须为正整数");
    }

    private static boolean empty(String cursor) { return cursor == null || cursor.isBlank(); }

    private static Long positiveCursor(String cursor) {
        if (empty(cursor)) return null;
        try {
            long value = Long.parseLong(cursor);
            if (value > 0) return value;
        } catch (NumberFormatException ignored) {}
        throw new BizException(ErrorCode.INVALID_ARGUMENT, "分页游标无效");
    }

    private record EventCursor(long at, long id) {}

    private static EventCursor eventCursor(String cursor) {
        if (empty(cursor)) return null;
        try {
            var parts = cursor.split("\\.", -1);
            if (parts.length == 2) {
                long at = Long.parseLong(parts[0]);
                long id = Long.parseLong(parts[1]);
                if (at >= 0 && id > 0) return new EventCursor(at, id);
            }
        } catch (NumberFormatException ignored) {}
        throw new BizException(ErrorCode.INVALID_ARGUMENT, "明细游标无效");
    }

    private static <T> Page<T> page(List<T> rows, int size, Function<T, String> cursor) {
        boolean more = rows.size() > size;
        var items = List.copyOf(rows.subList(0, Math.min(size, rows.size())));
        return new Page<>(items, more ? cursor.apply(items.get(items.size() - 1)) : null);
    }
}
