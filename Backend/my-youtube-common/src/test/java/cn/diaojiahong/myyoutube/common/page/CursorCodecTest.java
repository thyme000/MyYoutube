package cn.diaojiahong.myyoutube.common.page;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CursorCodecTest {
    private final CursorCodec<Position> codec = new CursorCodec<>(Position.class, 1024, "游标无效，请重新加载");

    record Position(long id, LocalDateTime time, List<Long> excluded) {
    }

    @Test
    void roundTripPreservesTimeAndLargeIdsWithUrlSafeEncoding() {
        long id = 9007199254740993L;
        Position position = new Position(id, LocalDateTime.of(2026, 10, 28, 1, 2, 3), List.of(id));
        String token = codec.encode(position);
        assertTrue(token.matches("[A-Za-z0-9_-]+"));
        assertEquals(position, codec.decode(token, decoded -> assertEquals(id, decoded.id())));
    }

    @Test
    void absentCursorMeansFirstPageWithoutInvokingBusinessValidation() {
        for (String value : new String[]{null, "", "  "}) {
            assertNull(codec.decode(value, ignored -> fail("No payload to validate")));
        }
    }

    @Test
    void rejectsMalformedOversizedAndNullPayloadsUsingTheConfiguredMessage() {
        for (String value : List.of("!invalid!", "a".repeat(1025), token("null"), token("[1]"), token("{\"id\":"))) {
            BizException rejected = assertThrows(BizException.class,
                    () -> codec.decode(value, ignored -> fail("Malformed payload must not reach business validation")));
            assertEquals(ErrorCode.INVALID_ARGUMENT, rejected.getErrorCode());
            assertEquals("游标无效，请重新加载", rejected.getMessage());
        }
    }

    @Test
    void businessValidationStillRejectsValidJsonWithoutExposingInternalExceptions() {
        String token = codec.encode(new Position(1, null, List.of()));
        BizException rejected = assertThrows(BizException.class, () -> codec.decode(token, position -> {
            throw new IllegalArgumentException("internal validation details");
        }));
        assertEquals("游标无效，请重新加载", rejected.getMessage());

        BizException specific = new BizException(ErrorCode.INVALID_ARGUMENT, "游标对应资源已不可用");
        assertSame(specific, assertThrows(BizException.class, () -> codec.decode(token, position -> {
            throw specific;
        })));
    }

    private String token(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
