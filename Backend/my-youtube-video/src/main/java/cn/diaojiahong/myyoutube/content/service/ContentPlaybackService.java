package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.monitoring.ObservedBusiness;
import cn.diaojiahong.myyoutube.common.monitoring.BusinessOperation;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.content.domain.dto.ContentPlaybackDTO;
import cn.diaojiahong.myyoutube.content.domain.vo.ContentPlaybackVO;
import cn.diaojiahong.myyoutube.content.mapper.ContentPlaybackMapper;
import cn.diaojiahong.myyoutube.content.strategy.ContentTypeStrategyRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ContentPlaybackService {
    private final ContentAccessService access;
    private final ContentTypeStrategyRegistry strategies;
    private final ContentPlaybackMapper playback;

    public ContentPlaybackVO get(Long contentId, Long viewer) {
        access.readable(contentId, viewer);
        return playback.find(viewer, contentId);
    }

    @Transactional
    @ObservedBusiness(BusinessOperation.SAVE_PLAYBACK)
    public void save(Long contentId, Long viewer, ContentPlaybackDTO state) {
        var content = access.requireReadable(access.locked(contentId), viewer);
        var now = System.currentTimeMillis();
        if (state == null || state.sessionId() == null || !state.sessionId().matches("[a-zA-Z0-9-]{1,64}")
                || state.sessionStartedAt() == null || state.sessionStartedAt() <= 0 || state.sessionStartedAt() > now + 60_000
                || state.sequence() == null || state.sequence() <= 0 || state.watchedMs() == null || state.watchedMs() < 0
                || state.watchedMs() > now - state.sessionStartedAt() + 61_000) throw invalid();
        var media = strategies.get(content.getType()).loadMedia(List.of(contentId)).get(contentId);
        if (media == null) throw invalid();
        if (media.video() != null) {
            if (!within(state.videoPositionMs(), media.video().durationMs()) || state.imageIndex() != null
                    || state.imageElapsedMs() != null || state.audioPositionMs() != null) throw invalid();
        } else {
            var dynamic = media.dynamic();
            var index = state.imageIndex();
            if (state.videoPositionMs() != null || index == null || index < 0 || index >= dynamic.items().size()
                    || !within(state.imageElapsedMs(), dynamic.items().get(index).durationMs() - 1)) throw invalid();
            if (dynamic.audio() == null ? state.audioPositionMs() != null
                    : !within(state.audioPositionMs(), dynamic.audio().durationMs())) throw invalid();
        }
        playback.save(viewer, contentId, state);
    }
    private static boolean within(Long value, long max) { return value != null && value >= 0 && value <= max; }
    private static BizException invalid() { return new BizException(ErrorCode.INVALID_ARGUMENT, "播放记录无效"); }
}
