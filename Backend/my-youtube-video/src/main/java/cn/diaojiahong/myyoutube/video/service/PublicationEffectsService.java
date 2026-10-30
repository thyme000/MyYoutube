package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.common.monitoring.ObservedBusiness;
import cn.diaojiahong.myyoutube.common.monitoring.BusinessOperation;
import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.PublicationEffectMessage;
import cn.diaojiahong.myyoutube.common.mq.message.PublicationBranch;
import cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.content.service.AudioContentAccess;
import cn.diaojiahong.myyoutube.notification.domain.po.UserNotification;
import cn.diaojiahong.myyoutube.notification.service.*;
import cn.diaojiahong.myyoutube.notification.support.ReviewFeedback;
import cn.diaojiahong.myyoutube.search.api.ContentDocPort;
import cn.diaojiahong.myyoutube.video.api.enums.ContentStatusEnum;
import cn.diaojiahong.myyoutube.video.domain.po.ContentPublicationEvent;
import cn.diaojiahong.myyoutube.video.mapper.ContentPublicationEventMapper;
import cn.diaojiahong.myyoutube.video.mapper.ContentReviewRecordMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PublicationEffectsService {
    private final ContentPublicationEventMapper events;
    private final ContentDocPort search;
    private final IUserNotificationService notifications;
    private final PushFanoutPublisher push;
    private final TransactionTemplate transaction;
    private final RocketMQTemplate mq;
    private final ContentReviewRecordMapper reviewRecords;
    private final AudioHistoricalAuthorCountService audioCounts;
    private final ContentMapper contents;
    private final AudioContentAccess audioAccess;

    public void trigger(Long eventId) {
        ContentPublicationEvent event = events.selectById(eventId);
        if (event == null || Boolean.TRUE.equals(event.getCompleted())) return;
        for (PublicationBranch branch : PublicationBranch.values()) {
            if (branch == PublicationBranch.AUDIO_COUNT && Boolean.TRUE.equals(event.getAudioCountDone())) continue;
            try {
                mq.syncSend(
                        MqConstants.TOPIC_PUBLICATION_EFFECT + ":" + branch,
                        new PublicationEffectMessage(eventId, branch));
            } catch (Exception e) {
                log.error("Publication branch deferred eventId={} branch={}", eventId, branch, e);
            }
        }
    }

    @Scheduled(fixedDelay = 300000, initialDelay = 300000)
    @ObservedBusiness(BusinessOperation.RECOVER_PUBLICATION)
    public void recover() {
        long cursor = 0;
        while (true) {
            List<ContentPublicationEvent> page = events.selectList(new LambdaQueryWrapper<ContentPublicationEvent>().gt(
                    ContentPublicationEvent::getId,
                    cursor)
                    .eq(ContentPublicationEvent::getCompleted, false)
                    .orderByAsc(ContentPublicationEvent::getId)
                    .last("LIMIT 100"));
            if (page.isEmpty()) return;
            cursor = page.get(page.size()-1).getId();
            for (ContentPublicationEvent event : page) {
                if (!Boolean.TRUE.equals(event.getSearchDone())) send(event.getId(), PublicationBranch.SEARCH);
                if (!Boolean.TRUE.equals(event.getNoticeDone())) send(event.getId(), PublicationBranch.NOTICE);
                if (!Boolean.TRUE.equals(event.getAudioCountDone())) send(event.getId(), PublicationBranch.AUDIO_COUNT);
            }
        }
    }

    private void send(Long id, PublicationBranch branch) {
        try {
            mq.syncSend(MqConstants.TOPIC_PUBLICATION_EFFECT + ":" + branch, new PublicationEffectMessage(id, branch));
        } catch (Exception e) {
            log.error("Publication recovery send failed eventId={} branch={}", id, branch, e);
        }
    }

    public void apply(PublicationEffectMessage message) {
        ContentPublicationEvent event = events.selectById(message.eventId());
        if (event == null || Boolean.TRUE.equals(event.getCompleted())) {
            log.debug("Publication branch ignored eventId={} branch={} reason={}", message.eventId(), message.branch(),
                    event == null ? "EVENT_MISSING" : "ALREADY_COMPLETED");
            return;
        }
        switch (message.branch()) {
            case SEARCH -> {
                if (!Boolean.TRUE.equals(event.getSearchDone())) {
                    search.rebuildContent(event.getContentId());
                    events.update(null, update(event).set(ContentPublicationEvent::getSearchDone, true));
                }
            }
            case NOTICE -> notice(event);
            case AUDIO_COUNT -> {
                if (!Boolean.TRUE.equals(event.getAudioCountDone())) audioCounts.apply(event.getAudioId());
            }
        }
        int completed = events.completeIfReady(List.of(event.getId()));
        if (completed > 0) {
            log.info("Publication effects completed eventId={} contentId={} authorId={} status={}",
                    event.getId(), event.getContentId(), event.getAuthorId(), event.getStatus().getCode());
        } else {
            log.debug(
                    "Publication branch applied, waiting for remaining branches eventId={} branch={}",
                    event.getId(),
                    message.branch());
        }
    }

    private void notice(ContentPublicationEvent event) {
        if (Boolean.TRUE.equals(event.getNoticeDone())) return;
        boolean inserted = Boolean.TRUE.equals(transaction.execute(tx -> {
            ContentPublicationEvent locked = events.selectOne(new LambdaQueryWrapper<ContentPublicationEvent>().eq(ContentPublicationEvent::getId, event.getId()).last("FOR UPDATE"));
            if (Boolean.TRUE.equals(locked.getNoticeDone())) return false;
            // 通过事件可能晚于删除或封禁到达；跳过过时通知，同时确认进度，避免补扫重复投递。
            if (obsoleteApproval(locked)) {
                events.update(null, update(locked).set(ContentPublicationEvent::getNoticeDone, true));
                return false;
            }
            boolean approved = event.getStatus() == ContentStatusEnum.PUBLISHED;
            boolean audioBanned = !approved
                    && reviewRecords.audioBannedContentIds(List.of(event.getContentId()))
                            .contains(event.getContentId());
            ReviewFeedback.ReviewNotice feedback = ReviewFeedback.reviewNotice(approved, audioBanned);
            boolean saved = notifications.save(new UserNotification().setReceiverId(event.getAuthorId()).setType("SYSTEM_NOTICE").setRefType("CONTENT")
                    .setRefId(event.getContentId())
                    .setTitle(feedback.title())
                    .setContent(feedback.content())
                    .setIsRead(0));
            if (!saved) throw new IllegalStateException("审核通知落库失败");
            events.update(null, update(event).set(ContentPublicationEvent::getNoticeDone, true));
            return true;
        }));
        if (inserted) {
            log.info("Publication notice stored eventId={} contentId={} receiverId={} status={}",
                    event.getId(), event.getContentId(), event.getAuthorId(), event.getStatus().getCode());
            try {
                push.pushSseSignal(event.getAuthorId(), NotificationPublisher.NOTIFY_DIRTY_SIGNAL);
            } catch (Exception e) {
                log.debug("SSE reminder failed after durable notification", e);
            }
        }
    }

    private boolean obsoleteApproval(ContentPublicationEvent event) {
        if (event.getStatus() != ContentStatusEnum.PUBLISHED) return false;
        Content content = contents.selectById(event.getContentId());
        return content == null || !Integer.valueOf(0).equals(content.getIsDelete())
                || content.getStatus() != ContentStatusEnum.PUBLISHED
                || !audioAccess.blocked(List.of(content)).isEmpty();
    }

    private LambdaUpdateWrapper<ContentPublicationEvent> update(ContentPublicationEvent event) {
        return new LambdaUpdateWrapper<ContentPublicationEvent>().eq(ContentPublicationEvent::getId, event.getId());
    }
}
