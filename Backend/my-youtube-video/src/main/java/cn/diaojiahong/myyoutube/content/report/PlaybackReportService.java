package cn.diaojiahong.myyoutube.content.report;

import cn.diaojiahong.myyoutube.common.media.ContentMedia;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.content.strategy.ContentTypeStrategy;
import cn.diaojiahong.myyoutube.content.strategy.ContentTypeStrategyRegistry;
import cn.diaojiahong.myyoutube.history.mapper.UserViewHistoryMapper;
import cn.diaojiahong.myyoutube.recommend.domain.enums.EventEnum;
import cn.diaojiahong.myyoutube.recommend.mapper.UserVideoBehaviorMapper;
import cn.diaojiahong.myyoutube.recommend.service.IUserTagPreferenceService;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 统一播放上报的 MySQL 落库规则(消费侧): 历史按实际展示记, PLAY/SKIP/FINISH 按后端判定登记,
 * 播放量跟随首次登记的 PLAY 行——行为表唯一键按会话判重, 会话期即播放量窗口。
 * 登录账号、作品类型与作品总时长均以后端数据为准。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PlaybackReportService {

    /** 累计有效播放达到内容总时长的 70% 时登记 PLAY。 */
    private static final double PLAY_RATIO = 0.7;

    private static final long SKIP_FOREGROUND_LIMIT_MS = 1000;

    private final UserViewHistoryMapper historyMapper;
    private final UserVideoBehaviorMapper behaviorMapper;
    private final ContentMapper contentMapper;
    private final IUserTagPreferenceService tagPreferences;
    private final ContentTypeStrategyRegistry strategies;

    /**
     * 整批一个事务。重投靠唯一键和版本规则幂等，旧的接收时间不会覆盖更新的续播位置。
     */
    @Transactional
    public void apply(Long userId, List<PlaybackReportDTO> reports) {
        if (reports == null || reports.isEmpty()) {
            return;
        }
        Map<Long, Content> contents = loadExistingContents(reports);
        Map<Long, ContentMedia> mediaById = loadMedia(contents);
        for (PlaybackReportDTO report : reports) {
            Long contentId = report.getContentId();
            Content content = contents.get(contentId);
            // 消费者确认作品存在: 已删除的内容整条跳过, 封禁/转私密仍保留此前发生的观看
            if (content == null) {
                continue;
            }
            Long foregroundMs = report.getForegroundMs();
            if (foregroundMs == null || foregroundMs <= 0) {
                continue;
            }

            ContentMedia media = mediaById.get(contentId);
            if (media == null) {
                log.warn("跳过播放上报，媒体资料缺失 contentId={} type={}", contentId, content.getType());
                continue;
            }

            ContentTypeStrategy strategy = strategies.get(content.getType());
            long totalDurationMs = media.durationMs();
            Long maxResumePositionMs = strategy.maxResumePositionMs(media);
            if (totalDurationMs <= 0 || maxResumePositionMs == null) {
                log.warn("跳过播放上报，媒体时长或续播范围无效 contentId={} type={} durationMs={} maxResumePositionMs={}",
                        contentId, content.getType(), totalDurationMs, maxResumePositionMs);
                continue;
            }

            long resumePositionMs = Math.min(report.getPositionMs(), maxResumePositionMs);
            long receivedAt = report.getReceivedAt();
            LocalDateTime viewTime = toLocalDateTime(receivedAt);
            historyMapper.upsertPlayback(userId, contentId, viewTime, resumePositionMs, receivedAt);

            EnumSet<EventEnum> events = detectEvents(report, media, strategy, totalDurationMs);
            recordEvents(userId, report, events);
        }
    }

    private EnumSet<EventEnum> detectEvents(PlaybackReportDTO report, ContentMedia media,
                                          ContentTypeStrategy strategy, long totalDurationMs) {
        long effectiveMs = report.getEffectiveMs();
        boolean playReached = effectiveMs >= totalDurationMs * PLAY_RATIO;

        EnumSet<EventEnum> events = EnumSet.noneOf(EventEnum.class);
        if (playReached) {
            events.add(EventEnum.PLAY);
        } else if (report.getForegroundMs() < SKIP_FOREGROUND_LIMIT_MS) {
            // 上报的是已结束的观看，短停留可直接判定为 SKIP。
            events.add(EventEnum.SKIP);
        }

        if (strategy.isFinished(media, effectiveMs, report.getPositionMs())) {
            events.add(EventEnum.FINISH);
        }
        return events;
    }

    private void recordEvents(Long userId, PlaybackReportDTO report, EnumSet<EventEnum> events) {
        for (EventEnum event : events) {
            int inserted = behaviorMapper.insertIgnore(IdWorker.getId(), userId, report.getContentId(),
                    report.getSessionId(), event.getCode(), report.getEffectiveMs());
            if (inserted != 1) {
                continue;
            }
            // 推荐分只跟首次登记的行为走: 重投/重复上报被唯一键挡住后不重复计分
            tagPreferences.addBehaviorScore(userId, report.getContentId(), event);
            // 播放量跟随首次登记的 PLAY 行: 会话期即播放量窗口, 同会话同内容至多计一次, 无需第二道闸
            if (event == EventEnum.PLAY) {
                contentMapper.update(null, new LambdaUpdateWrapper<Content>()
                        .setSql("play_count = play_count + 1")
                        .eq(Content::getId, report.getContentId()));
            }
        }
    }

    private Map<Long, Content> loadExistingContents(List<PlaybackReportDTO> reports) {
        List<Long> contentIds = reports.stream().map(PlaybackReportDTO::getContentId).distinct().toList();
        return contentMapper.selectList(new LambdaQueryWrapper<Content>()
                    .in(Content::getId, contentIds)
                    .eq(Content::getIsDelete, 0))
                    .stream()
                    .collect(Collectors.toMap(Content::getId, content -> content));
    }

    private Map<Long, ContentMedia> loadMedia(Map<Long, Content> contents) {
        Map<Long, ContentMedia> mediaById = new HashMap<>();
        Map<ContentTypeEnum, List<Long>> idsByType = contents.values().stream()
                .collect(Collectors.groupingBy(Content::getType, Collectors.mapping(Content::getId, Collectors.toList())));
        idsByType.forEach((type, ids) -> mediaById.putAll(strategies.get(type).loadMedia(ids)));
        return mediaById;
    }

    private LocalDateTime toLocalDateTime(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
    }
}
