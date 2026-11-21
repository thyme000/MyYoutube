package cn.diaojiahong.myyoutube.chat.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MessagePreviewTest {

    @Test
    void shortTextStaysWhole() {
        assertNull(MessagePreview.clip(null));
        assertEquals("你好", MessagePreview.clip("你好"));
    }

    @Test
    void longTextKeepsTheFirstFortyCharacters() {
        String preview = "字".repeat(41);
        String clipped = MessagePreview.clip(preview);
        assertEquals(40, clipped.codePointCount(0, clipped.length()));
        assertEquals("字".repeat(40), clipped);
    }
}
