package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.mq.MqSendUnconfirmedException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.content.report.PlaybackReportController;
import cn.diaojiahong.myyoutube.content.report.PlaybackReportDTO;
import cn.diaojiahong.myyoutube.content.report.PlaybackReportMessage;
import cn.diaojiahong.myyoutube.content.report.PlaybackReportPublisher;
import cn.diaojiahong.myyoutube.content.report.PlaybackReportStore;
import cn.diaojiahong.myyoutube.content.service.ContentAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 统一播放上报端点行为测试: 端点盖章会话与接收时间，确认投递后才回执成功，契约校验。
 */
class PlaybackReportControllerTest {

    final PlaybackReportStore store = mock(PlaybackReportStore.class);
    final PlaybackReportPublisher publisher = mock(PlaybackReportPublisher.class);
    final ContentAccessService access = mock(ContentAccessService.class);
    final PlaybackReportController controller = new PlaybackReportController(store, publisher, access);

    @BeforeEach
    void setup() {
        UserContext.setUser("9");
        when(store.sessionFor(9L)).thenReturn("session-1");
        when(access.readableIds(any(), eq(9L))).thenReturn(Set.of(1L, 2L));
    }

    @AfterEach
    void clear() {
        UserContext.removeUser();
    }

    private PlaybackReportDTO report(long contentId, long effectiveMs, long foregroundMs, Long position) {
        PlaybackReportDTO dto = new PlaybackReportDTO();
        dto.setContentId(contentId);
        dto.setEffectiveMs(effectiveMs);
        dto.setForegroundMs(foregroundMs);
        dto.setPositionMs(position);
        return dto;
    }

    @Test
    void stampsReportsThenSendsThem() {
        PlaybackReportDTO first = report(1, 600, 600, 0L);
        PlaybackReportDTO second = report(2, 7000, 7000, 5000L);
        List<PlaybackReportDTO> reports = List.of(first, second);

        Result<Void> result = controller.report(reports);

        assertEquals(Result.SUCCESS_CODE, result.getCode());
        // 会话与接收时间由端点盖章, 客户端传入被覆盖, 整批共用一份
        assertEquals("session-1", first.getSessionId());
        assertEquals("session-1", second.getSessionId());
        assertTrue(first.getReceivedAt() > 0);
        assertEquals(first.getReceivedAt(), second.getReceivedAt());
        verify(store).sessionFor(9L);
        verify(publisher).send(new PlaybackReportMessage(9L, reports));
    }

    @Test
    void dropsUnreadableContentWhileSendingTheRest() {
        PlaybackReportDTO readable = report(1, 600, 600, 0L);
        PlaybackReportDTO unreadable = report(3, 7000, 7000, 5000L);

        assertEquals(Result.SUCCESS_CODE, controller.report(List.of(readable, unreadable)).getCode());

        verify(access).readableIds(List.of(1L, 3L), 9L);
        verify(publisher).send(new PlaybackReportMessage(9L, List.of(readable)));
    }

    @Test
    void unconfirmedDeliveryFailsTheRequest() {
        PlaybackReportDTO report = report(1, 600, 600, 0L);
        doThrow(new MqSendUnconfirmedException(null)).when(publisher).send(any());

        assertThrows(MqSendUnconfirmedException.class, () -> controller.report(List.of(report)));
    }

    @Test
    void rejectsContractViolationsBeforeSending() {
        // 有效播放超过前台展示: 不是前台展示的子集
        assertThrows(BizException.class, () -> controller.report(List.of(report(1, 700, 600, 0L))));
        // 数量级矛盾
        assertThrows(BizException.class, () -> controller.report(List.of(report(1, 10 * 60_000, 600, 0L))));
        // 负数累计
        assertThrows(BizException.class, () -> controller.report(List.of(report(1, -1, 600, 0L))));
        assertThrows(BizException.class, () -> controller.report(List.of(report(1, 600, -1, 0L))));
        // 位置必须明确上报，且不能为负。
        BizException missingPosition = assertThrows(BizException.class,
                () -> controller.report(List.of(report(1, 600, 600, null))));
        assertEquals(ErrorCode.INVALID_ARGUMENT, missingPosition.getErrorCode());
        assertThrows(BizException.class, () -> controller.report(List.of(report(1, 600, 600, -1L))));
        assertThrows(BizException.class, () -> controller.report(List.of()));
        // 内容 ID 非法
        assertThrows(BizException.class, () -> controller.report(List.of(report(0, 600, 600, 0L))));
        verifyNoInteractions(store, publisher);

        // 有效播放等于前台展示且位置为 0 时，上报仍然有效。
        PlaybackReportDTO equality = report(1, 600, 600, 0L);
        controller.report(List.of(equality));

        verify(publisher).send(new PlaybackReportMessage(9L, List.of(equality)));
    }

    @Test
    void overSizedBatchIsRejected() {
        List<PlaybackReportDTO> reports = new ArrayList<>();
        for (long i = 1; i <= 101; i++) {
            reports.add(report(i, 600, 600, 0L));
        }
        BizException failure = assertThrows(BizException.class, () -> controller.report(reports));
        assertEquals(ErrorCode.INVALID_ARGUMENT, failure.getErrorCode());
        verifyNoInteractions(store, publisher);
    }
}
