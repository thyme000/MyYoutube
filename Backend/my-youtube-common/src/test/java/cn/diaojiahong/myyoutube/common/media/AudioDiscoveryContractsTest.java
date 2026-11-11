package cn.diaojiahong.myyoutube.common.media;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class AudioDiscoveryContractsTest {
    @ParameterizedTest @ValueSource(ints = {0, -1, 101})
    void invalidPageSizeCannotReachSearch(int size) {
        BizException error = assertThrows(
                BizException.class,
                () -> new AudioDiscoveryContracts.Query(null, null, null, size).validate());
        assertEquals(ErrorCode.INVALID_ARGUMENT, error.getErrorCode());
    }

    @Test
    void pageSizeWithinLimitIsAccepted() {
        assertDoesNotThrow(() -> new AudioDiscoveryContracts.Query("雨声", null, null, 100).validate());
    }

    @Test
    void keywordAndOwnerLimitsApplyToBothEntryPoints() {
        assertDoesNotThrow(() -> new AudioDiscoveryContracts.Query("音".repeat(100), 9L, null, 10).validate());
        assertThrows(
                BizException.class,
                () -> new AudioDiscoveryContracts.Query("音".repeat(101), null, null, 10).validate());
        assertThrows(BizException.class, () -> new AudioDiscoveryContracts.Query(null, 0L, null, 10).validate());
        assertThrows(BizException.class, () -> new AudioDiscoveryContracts.Query(null, -1L, null, 10).validate());
    }
}
