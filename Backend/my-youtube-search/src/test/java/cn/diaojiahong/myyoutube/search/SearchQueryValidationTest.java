package cn.diaojiahong.myyoutube.search;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.CursorPageQuery;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.search.domain.dto.SearchQueryValidation;
import cn.diaojiahong.myyoutube.search.domain.dto.TagSearchCursorQuery;
import cn.diaojiahong.myyoutube.search.domain.dto.ContentSearchCursorQuery;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SearchQueryValidationTest {
    @Test void bothSearchInputsRetainInheritedDefaultsAndPageSizeValidation() {
        for (CursorPageQuery query : new CursorPageQuery[]{new ContentSearchCursorQuery(), new TagSearchCursorQuery()}) {
            assertEquals(10, query.getPageSize());
            assertNull(query.getCursor());
            assertDoesNotThrow(query::validate);
            query.setPageSize(PageQuery.MAX_PAGE_SIZE);
            assertDoesNotThrow(query::validate);

            for (int size : new int[]{0, -1, PageQuery.MAX_PAGE_SIZE + 1}) {
                query.setPageSize(size);
                assertThrows(BizException.class, query::validate);
            }
        }
    }

    @Test void bothSearchInputsAcceptTheBoundaryAndRejectLongerKeywords() {
        ContentSearchCursorQuery content = new ContentSearchCursorQuery();
        TagSearchCursorQuery tag = new TagSearchCursorQuery();
        String maximum = "音".repeat(SearchQueryValidation.MAX_KEYWORD_LENGTH);
        content.setKeyword(maximum);
        tag.setTagName(maximum);
        assertDoesNotThrow(content::validate);
        assertDoesNotThrow(tag::validate);

        content.setKeyword(maximum + "乐");
        tag.setTagName(maximum + "乐");
        assertThrows(BizException.class, content::validate);
        assertThrows(BizException.class, tag::validate);
    }

    @Test void supplementaryCharactersCountAsOneCharacterAndEmptySearchRemainsAllowed() {
        assertDoesNotThrow(() -> SearchQueryValidation.validateKeyword("🎵".repeat(SearchQueryValidation.MAX_KEYWORD_LENGTH)));
        assertThrows(BizException.class, () -> SearchQueryValidation.validateKeyword("🎵".repeat(SearchQueryValidation.MAX_KEYWORD_LENGTH + 1)));
        assertDoesNotThrow(() -> SearchQueryValidation.validateKeyword(null));
        assertDoesNotThrow(() -> SearchQueryValidation.validateKeyword(""));
    }
}
