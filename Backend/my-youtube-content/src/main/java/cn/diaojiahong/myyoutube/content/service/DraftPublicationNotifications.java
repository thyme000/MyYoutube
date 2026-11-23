package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import cn.diaojiahong.myyoutube.common.mq.message.DraftPublicationMessage;
import cn.diaojiahong.myyoutube.content.api.enums.DraftSubmissionState;
import cn.diaojiahong.myyoutube.content.domain.po.MediaDraft;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DraftPublicationNotifications {
    private final AfterCommitNotifications notifications;

    public void afterCommit(MediaDraft draft) {
        if (draft == null || draft.getSubmissionState() != DraftSubmissionState.PENDING) return;
        var message = new DraftPublicationMessage(draft.getId(), draft.getSubmissionRevision());
        notifications.send(DraftPublicationMessage.TOPIC, message, MetricNames.PUBLICATION_NOTIFICATION_FAILED);
    }
}
