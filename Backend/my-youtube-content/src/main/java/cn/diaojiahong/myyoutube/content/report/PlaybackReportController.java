package cn.diaojiahong.myyoutube.content.report;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.content.service.ContentAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * 统一播放上报端点: 盖章会话与接收时间，MQ 确认投递后才回执成功。
 * 失败由前端从蓄水池取最新快照重传。
 */
@RestController
@RequiredArgsConstructor
public class PlaybackReportController {

    /** 单批上限: 前端按条数/时间条件批量发, 上限只防异常客户端塞爆批次 */
    private static final int MAX_REPORTS_PER_REQUEST = 100;

    private final PlaybackReportStore store;
    private final PlaybackReportPublisher publisher;
    private final ContentAccessService access;

    @PostMapping("/contents/playback/reports")
    public Result<Void> report(@RequestBody List<PlaybackReportDTO> reports) {
        long userId = UserContext.requireUserId();
        if (reports == null || reports.isEmpty() || reports.size() > MAX_REPORTS_PER_REQUEST) {
            throw invalid();
        }
        reports.forEach(this::validate);

        long receivedAt = System.currentTimeMillis();
        List<Long> contentIds = reports.stream().map(PlaybackReportDTO::getContentId).distinct().toList();
        Set<Long> readableIds = access.readableIds(contentIds, userId);
        List<PlaybackReportDTO> readableReports = reports.stream()
                .filter(report -> readableIds.contains(report.getContentId()))
                .toList();
        if (readableReports.isEmpty()) {
            return Result.success();
        }

        String sessionId = store.sessionFor(userId);
        for (PlaybackReportDTO report : readableReports) {
            report.setSessionId(sessionId);
            report.setReceivedAt(receivedAt);
        }

        publisher.send(new PlaybackReportMessage(userId, readableReports));
        return Result.success();
    }

    private void validate(PlaybackReportDTO report) {
        boolean invalid = report == null
                || report.getContentId() == null || report.getContentId() <= 0
                || report.getForegroundMs() == null || report.getForegroundMs() < 0
                || report.getEffectiveMs() == null || report.getEffectiveMs() < 0
                // 有效播放是前台展示的子集: 前端同一时钟结算, 精确边界成立
                || report.getEffectiveMs() > report.getForegroundMs()
                || report.getPositionMs() == null || report.getPositionMs() < 0;
        if (invalid) {
            throw invalid();
        }
    }

    private static BizException invalid() {
        return new BizException(ErrorCode.INVALID_ARGUMENT, "播放上报无效");
    }
}
