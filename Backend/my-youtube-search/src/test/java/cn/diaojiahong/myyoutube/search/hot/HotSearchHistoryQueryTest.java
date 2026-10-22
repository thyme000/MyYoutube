package cn.diaojiahong.myyoutube.search.hot;

import cn.diaojiahong.myyoutube.search.api.HotSearchHistoryPort.*;
import cn.diaojiahong.myyoutube.search.mapper.HotSearchHistoryMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HotSearchHistoryQueryTest {
    final LocalDate day = LocalDate.of(2026, 10, 20);
    final HotSearchHistoryMapper mapper = mock(HotSearchHistoryMapper.class);
    final HotSearchProperties properties = new HotSearchProperties();
    final HotSearchHistoryQueryService service = new HotSearchHistoryQueryService(mapper, new HotSearchFilter(properties),
            properties, Clock.fixed(Instant.parse("2026-10-20T16:30:00Z"), ZoneOffset.UTC));

    Group group(String id, String name, long rank) { return new Group(id, name, "v1", 3, 3, 3, rank, null); }

    @Test void publicTopSevenFiltersAndDeduplicatesBeforeTakingSevenAcrossPages() {
        var blocked = IntStream.range(0, 200).mapToObj(i -> group("b" + i, "1111", i + 1)).toList();
        var next = new ArrayList<Group>();
        next.add(group("duplicate", "正常主题一", 201));
        next.add(group("duplicate-v2", "正常主题一", 202));
        for (int i = 0; i < 8; i++) next.add(group("v" + i, "正常主题" + i, 203 + i));
        when(mapper.groups(day, null, 3, 200)).thenReturn(blocked);
        when(mapper.groups(day, 200L, 3, 200)).thenReturn(next);

        var names = service.hot(day.toString());

        assertEquals(7, names.size());
        assertEquals("正常主题一", names.get(0));
        assertEquals(7, names.stream().distinct().count());
        verify(mapper).groups(day, 200L, 3, 200);
    }

    @Test void rejectsTodayUsingShanghaiTimeAcrossEveryHistoryQuery() {
        String today = "2026-10-21";
        assertThrows(IllegalArgumentException.class, () -> service.hot(today));
        assertThrows(IllegalArgumentException.class, () -> service.dates(today, 20));
        assertThrows(IllegalArgumentException.class, () -> service.groups(today, null, 20));
        assertThrows(IllegalArgumentException.class, () -> service.keywords(today, "g", null, 20));
        assertThrows(IllegalArgumentException.class, () -> service.users(today, "g", 1L, null, 20));
        assertThrows(IllegalArgumentException.class, () -> service.events(today, "g", 1L, null, null, 20));
        verifyNoInteractions(mapper);
    }

    @Test void invalidSizesDatesAndCursorsNeverReachTheDatabase() {
        for (int size : new int[]{0, -1, 101}) assertThrows(IllegalArgumentException.class, () -> service.groups(day.toString(), null, size));
        for (String date : List.of("2026-02-30", "not-a-date", "2026-1-1")) assertThrows(IllegalArgumentException.class, () -> service.hot(date));
        for (String cursor : List.of("0", "-1", "x", "9999999999999999999999")) {
            assertThrows(IllegalArgumentException.class, () -> service.groups(day.toString(), cursor, 20));
            assertThrows(IllegalArgumentException.class, () -> service.users(day.toString(), "g", 1L, cursor, 20));
        }
        for (String cursor : List.of("1", "-1.2", "2.0", "1.2.3", "x.1")) {
            assertThrows(IllegalArgumentException.class, () -> service.events(day.toString(), "g", 1L, null, cursor, 20));
        }
        assertThrows(IllegalArgumentException.class, () -> service.users(day.toString(), "g", -1L, null, 20));
        assertThrows(IllegalArgumentException.class, () -> service.keywords(day.toString(), " ", null, 20));
        verifyNoInteractions(mapper);
    }

    @Test void administratorPagesAreNotLimitedToSevenAndUseOnlyOneLookaheadRow() {
        var rows = IntStream.range(0, 11).mapToObj(i -> group("g" + i, "主题" + i, i + 1)).toList();
        when(mapper.groups(day, null, 0, 11)).thenReturn(rows);

        var page = service.groups(day.toString(), null, 10);

        assertEquals(10, page.items().size());
        assertEquals("10", page.nextCursor());
        when(mapper.groups(day, 10L, 0, 11)).thenReturn(List.of(rows.get(10)));
        assertNull(service.groups(day.toString(), page.nextCursor(), 10).nextCursor());
    }

    @Test void representativeKeywordIdMustBelongToTheRequestedDateAndGroup() {
        assertThrows(IllegalArgumentException.class, () -> service.users(day.toString(), "other", 1L, null, 20));
        verify(mapper).keyword(day, "other", 1L);
        verify(mapper, never()).users(any(), any(), any(), any(), anyInt());
    }

    @Test void detailCursorForwardsBothTimeAndIdWithoutChangingOriginalText() {
        when(mapper.keyword(day, "g", 7L)).thenReturn("  C++  入门  ");
        when(mapper.events(day, "g", "  C++  入门  ", 9L, 100L, 5L, 2)).thenReturn(List.of(
                new Event(6, "a", day, 100, 0, 9, "  C++  入门  ", "C++  入门", "g", "主题", "v1"),
                new Event(7, "b", day, 101, 0, 9, "  C++  入门  ", "C++  入门", "g", "主题", "v1")));

        var page = service.events(day.toString(), "g", 7L, 9L, "100.5", 1);

        assertEquals("100.6", page.nextCursor());
        assertEquals("  C++  入门  ", page.items().get(0).rawKeyword());
    }
}
