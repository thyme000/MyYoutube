package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.content.domain.vo.ContentPlaybackVO;
import cn.diaojiahong.myyoutube.history.mapper.UserViewHistoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 续播读取: 进度并入观看历史行, 与历史同生命周期(删除历史即删进度); 写入统一走播放上报消费侧。
 */
@Service
@RequiredArgsConstructor
public class ContentPlaybackService {

    private final ContentAccessService access;
    private final UserViewHistoryMapper historyMapper;

    public ContentPlaybackVO get(Long contentId, Long viewer) {
        access.readable(contentId, viewer);
        long positionMs = historyMapper.selectResumePosition(viewer, contentId);
        return new ContentPlaybackVO(positionMs);
    }
}
