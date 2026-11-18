package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.content.report.PlaybackReportDTO;
import cn.diaojiahong.myyoutube.content.report.PlaybackReportMessage;
import cn.diaojiahong.myyoutube.content.report.PlaybackReportService;
import cn.diaojiahong.myyoutube.mq.listener.PlaybackReportListener;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 播放上报消费: 消息正文直接落库，落库失败抛回 MQ 重投。
 */
class PlaybackReportListenerTest {

    final PlaybackReportService reportService = mock(PlaybackReportService.class);
    final PlaybackReportListener listener = new PlaybackReportListener(reportService);

    @Test
    void messageIsApplied() {
        List<PlaybackReportDTO> reports = List.of(new PlaybackReportDTO());
        PlaybackReportMessage message = new PlaybackReportMessage(9L, reports);

        listener.onMessage(message);

        verify(reportService).apply(9L, reports);
    }

    @Test
    void applyFailurePropagatesForRedelivery() {
        List<PlaybackReportDTO> reports = List.of(new PlaybackReportDTO());
        PlaybackReportMessage message = new PlaybackReportMessage(9L, reports);
        RuntimeException failure = new RuntimeException("db down");
        doThrow(failure).when(reportService).apply(9L, reports);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> listener.onMessage(message));

        assertSame(failure, thrown);
    }

    @Test
    void malformedMessageIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> listener.onMessage(null));
        assertThrows(IllegalArgumentException.class,
                () -> listener.onMessage(new PlaybackReportMessage(null, List.of(new PlaybackReportDTO()))));
        assertThrows(IllegalArgumentException.class, () -> listener.onMessage(new PlaybackReportMessage(9L, null)));
        assertThrows(IllegalArgumentException.class,
                () -> listener.onMessage(new PlaybackReportMessage(9L, List.of())));
        verify(reportService, never()).apply(any(), any());
    }
}
