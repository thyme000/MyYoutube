package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.content.domain.dto.AuthorContentCursor;
import cn.diaojiahong.myyoutube.content.domain.dto.AuthorContentCursorQuery;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class AuthorContentCursorTest {
    private final LocalDateTime time = LocalDateTime.of(2026, 10, 28, 0, 0);

    @Test void cursorPreservesLargeIdsAndRejectsChangedSearchConditions() {
        long id = 9007199254740993L;
        String value = new AuthorContentCursor(42, 9L, ContentTypeEnum.VIDEO, time, id).encode();
        assertEquals(id, AuthorContentCursor.decode(value, 42, 9L, ContentTypeEnum.VIDEO).contentId());
        assertThrows(BizException.class, () -> AuthorContentCursor.decode(value, 43, 9L, ContentTypeEnum.VIDEO));
        assertThrows(BizException.class, () -> AuthorContentCursor.decode(value, 42, 10L, ContentTypeEnum.VIDEO));
        assertThrows(BizException.class, () -> AuthorContentCursor.decode(value, 42, 9L, ContentTypeEnum.DYNAMIC));
        assertThrows(BizException.class, () -> AuthorContentCursor.decode(value, 42, 9L, null));
    }

    @Test void rejectsInvalidCursorBeforeAnyDatabaseWork() {
        assertNull(AuthorContentCursor.decode(null, 42, 9L, null));
        assertThrows(BizException.class, () -> AuthorContentCursor.decode("invalid", 42, 9L, null));
        assertThrows(BizException.class, () -> AuthorContentCursor.decode("a".repeat(1025), 42, 9L, null));
        assertThrows(BizException.class, () -> AuthorContentCursor.decode(
                new AuthorContentCursor(42, 9L, null, null, 1).encode(), 42, 9L, null));
        assertThrows(BizException.class, () -> AuthorContentCursor.decode(
                new AuthorContentCursor(42, 9L, null, time, 0).encode(), 42, 9L, null));
    }

    @Test void queryUsesCommonPageSizeValidationAndNormalizesMediaType() {
        AuthorContentCursorQuery query = new AuthorContentCursorQuery();
        assertEquals(10, query.getPageSize());
        query.setType("VIDEO");
        assertEquals(ContentTypeEnum.VIDEO, query.mediaType());
        assertEquals("video", query.getType());
        query.setType("invalid");
        assertThrows(BizException.class, query::mediaType);
        query.setType(null);
        query.setPageSize(0);
        assertThrows(BizException.class, query::mediaType);
        query.setPageSize(101);
        assertThrows(BizException.class, query::mediaType);
    }
}
