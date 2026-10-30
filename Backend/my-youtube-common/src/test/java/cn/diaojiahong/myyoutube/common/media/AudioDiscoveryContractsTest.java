package cn.diaojiahong.myyoutube.common.media;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class AudioDiscoveryContractsTest {
    @ParameterizedTest @CsvSource({"0,10", "-1,10", "1,0", "1,101", "100,100", "10000,1", "2147483647,100"})
    void invalidBoundsAndOverflowCannotReachSearch(int page, int size) {
        BizException error = assertThrows(
                BizException.class,
                () -> new AudioDiscoveryContracts.Query(null, null, page, size).validate());
        assertEquals(ErrorCode.INVALID_ARGUMENT, error.getErrorCode());
    }

    @ParameterizedTest @CsvSource({"1,1", "99,100", "9999,1", "3333,3"})
    void lastAllowedPagesReserveRoomForTheExtraCandidate(int page, int size) {
        assertDoesNotThrow(() -> new AudioDiscoveryContracts.Query("雨声", null, page, size).validate());
    }

    @Test
    void keywordAndOwnerLimitsApplyToBothEntryPoints() {
        assertDoesNotThrow(() -> new AudioDiscoveryContracts.Query("音".repeat(100), 9L, 1, 10).validate());
        assertThrows(
                BizException.class,
                () -> new AudioDiscoveryContracts.Query("音".repeat(101), null, 1, 10).validate());
        assertThrows(BizException.class, () -> new AudioDiscoveryContracts.Query(null, 0L, 1, 10).validate());
        assertThrows(BizException.class, () -> new AudioDiscoveryContracts.Query(null, -1L, 1, 10).validate());
    }
}
