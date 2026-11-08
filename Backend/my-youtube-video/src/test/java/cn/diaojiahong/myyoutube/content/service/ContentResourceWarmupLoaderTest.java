package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceWarmupProperties;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.user.api.FollowPort;
import cn.diaojiahong.myyoutube.video.api.enums.ContentStatusEnum;
import cn.diaojiahong.myyoutube.video.api.enums.ContentVisibilityEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentResourceWarmupLoaderTest {
    private final ContentMapper contents = mock(ContentMapper.class);
    private final AudioContentAccess audio = mock(AudioContentAccess.class);
    private final FollowPort followPort = mock(FollowPort.class);
    private final ContentResourceWarmupProperties properties = new ContentResourceWarmupProperties();
    private final ContentResourceWarmupLoader loader = new ContentResourceWarmupLoader(
            new ContentAccessService(contents, audio), followPort, properties);
    private final Content content = new Content().setId(1L).setAuthorId(9L).setIsDelete(0)
            .setStatus(ContentStatusEnum.PUBLISHED).setVisibility(ContentVisibilityEnum.PUBLIC.getCode()).setPlayCount(0L);

    @BeforeEach
    void setup() {
        when(contents.selectById(1L)).thenReturn(content);
        when(audio.blocked(List.of(content))).thenReturn(Set.of());
    }

    /** 与 ContentResourceWarmup.eligibleContent 相同的两步: 先读内容(可读性), 出事务后再判粉丝门槛 */
    private Content loadEligible(Long id) {
        Content loaded = loader.loadReadable(id);
        return loader.aboveFanThreshold(loaded) ? loaded : null;
    }

    @ParameterizedTest
    @ValueSource(longs = {10000, 10001})
    void authorAtOrAboveTheThresholdCanWarmANewContent(Long fans) {
        when(followPort.fansCount(9L)).thenReturn(fans);

        assertSame(content, loadEligible(1L));

        assertEquals(0L, content.getPlayCount());
        verify(contents).selectById(1L);
        verify(followPort).fansCount(9L);
    }

    @Test
    void fanThresholdIsConfigurableAndMissingFanCountIsTreatedAsZero() {
        properties.setMinFans(50000);
        when(followPort.fansCount(9L)).thenReturn(49999L, null, 50000L);

        assertNull(loadEligible(1L));
        assertNull(loadEligible(1L));
        assertSame(content, loadEligible(1L));
    }

    @Test
    void privateAndDeletedContentsAreRejectedBeforeCountingFans() {
        content.setVisibility(ContentVisibilityEnum.PRIVATE.getCode());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BizException.class, () -> loadEligible(1L)).getErrorCode());
        content.setVisibility(ContentVisibilityEnum.PUBLIC.getCode()).setIsDelete(1);
        assertThrows(BizException.class, () -> loadEligible(1L));
        when(contents.selectById(1L)).thenReturn(null);
        assertThrows(BizException.class, () -> loadEligible(1L));

        verifyNoInteractions(followPort);
    }

    @ParameterizedTest
    @EnumSource(value = ContentStatusEnum.class, names = "PUBLISHED", mode = EnumSource.Mode.EXCLUDE)
    void unpublishedContentCannotBeWarmed(ContentStatusEnum status) {
        content.setStatus(status);

        assertThrows(BizException.class, () -> loadEligible(1L));

        verifyNoInteractions(followPort);
    }

    @Test
    void bannedAudioIsRejectedUsingTheExistingAccessRule() {
        when(audio.blocked(List.of(content))).thenReturn(Set.of(1L));

        assertThrows(BizException.class, () -> loadEligible(1L));

        verifyNoInteractions(followPort);
    }

    @Test
    void fanLookupFailureIsNotMistakenForALowFanCount() {
        var failure = new IllegalStateException("database unavailable");
        when(followPort.fansCount(9L)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> loadEligible(1L)));
    }
}
