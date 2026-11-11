package cn.diaojiahong.myyoutube.search;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.search.domain.dto.SearchQueryValidation;
import cn.diaojiahong.myyoutube.search.domain.dto.TagSearchCursorQuery;
import cn.diaojiahong.myyoutube.search.domain.dto.ContentSearchCursorQuery;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SearchQueryValidationTest {
    @Test
    void bothSearchInputsStartWithoutACursor() {
        assertNull(new ContentSearchCursorQuery().getCursor());
        assertNull(new TagSearchCursorQuery().getCursor());
        assertDoesNotThrow(() -> new ContentSearchCursorQuery().validate());
        assertDoesNotThrow(() -> new TagSearchCursorQuery().validate());
    }

    @Test
    void bothSearchInputsAcceptTheBoundaryAndRejectLongerKeywords() {
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

    @Test
    void supplementaryCharactersCountAsOneCharacterAndEmptySearchRemainsAllowed() {
        assertDoesNotThrow(() -> SearchQueryValidation.validateKeyword("🎵".repeat(SearchQueryValidation.MAX_KEYWORD_LENGTH)));
        assertThrows(
                BizException.class,
                () -> SearchQueryValidation.validateKeyword("🎵".repeat(SearchQueryValidation.MAX_KEYWORD_LENGTH + 1)));
        assertDoesNotThrow(() -> SearchQueryValidation.validateKeyword(null));
        assertDoesNotThrow(() -> SearchQueryValidation.validateKeyword(""));
    }
}
