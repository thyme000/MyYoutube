package cn.diaojiahong.myyoutube.common.page;

import java.util.List;
import java.util.function.Function;

/**
 * 游标页。查询条数用 {@link #fetchSize}，比页长多一条。
 * {@link #slice} 把这次查询结果切成本页，这里不发查询。
 * 多出来的那一条只用来判断还有没有下一页，游标取本页最后一条。
 * {@code hasMore} 与 {@code nextCursor} 是同一次判断：还有下一页时游标非空。
 */
public record CursorPage<T, C>(List<T> items, C nextCursor, boolean hasMore) {

    public CursorPage(List<T> items, C nextCursor) {
        this(items, nextCursor, nextCursor != null);
    }

    /** 比给定条数多取一条，多出来的那一条用来判断后面还有没有数据。 */
    public static int fetchSize(int size) {
        return size + 1;
    }

    public static <T, C> CursorPage<T, C> slice(List<T> fetched, int pageSize, Function<T, C> cursorOf) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("页长须为正数");
        }

        boolean more = fetched.size() > pageSize;
        int visible = Math.min(pageSize, fetched.size());
        List<T> items = List.copyOf(fetched.subList(0, visible));

        C nextCursor = null;
        if (more) {
            nextCursor = cursorOf.apply(items.get(items.size() - 1));
        }
        return new CursorPage<>(items, nextCursor, more);
    }
}
