package cn.diaojiahong.myyoutube.common.page;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CursorPageTest {

    @Test
    void fetchSizeAsksForOneExtraRow() {
        assertEquals(3, CursorPage.fetchSize(2));
    }

    @Test
    void extraRowBecomesTheNextCursorOfTheLastVisibleItem() {
        CursorPage<String, String> page = CursorPage.slice(List.of("a", "b", "c"), 2, item -> item);

        assertEquals(List.of("a", "b"), page.items());
        assertEquals("b", page.nextCursor());
        assertTrue(page.hasMore());
    }

    @Test
    void exactAndShortPagesHaveNoNextCursor() {
        CursorPage<String, String> exact = CursorPage.slice(List.of("a", "b"), 2, item -> item);
        assertNull(exact.nextCursor());
        assertFalse(exact.hasMore());
        assertEquals(List.of("a"), CursorPage.slice(List.of("a"), 2, item -> item).items());
        assertNull(CursorPage.slice(List.of(), 2, item -> {
            throw new AssertionError("empty page has no cursor row");
        }).nextCursor());
    }

    @Test
    void zeroPageSizeIsRejectedWhenRowsWereFetched() {
        assertThrows(IllegalArgumentException.class, () -> CursorPage.slice(List.of("a"), 0, item -> item));
    }
}
