package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.video.api.enums.ContentStatusEnum;
import cn.diaojiahong.myyoutube.video.api.enums.ContentVisibilityEnum;
import cn.diaojiahong.myyoutube.video.domain.po.ContentPublicationEvent;
import cn.diaojiahong.myyoutube.video.mapper.ContentPublicationEventMapper;
import cn.diaojiahong.myyoutube.video.service.PublicationEffectsService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;

@Service
@RequiredArgsConstructor
public class ContentSettingsService {
    private final ContentAccessService access;
    private final ContentMapper contents;
    private final ContentPublicationEventMapper events;
    private final PublicationEffectsService effects;
    private final ContentStatisticsService statistics;

    public record Settings(String visibility, Integer allowComment) {
    }

    @Transactional
    public void update(Long id, Long viewer, Settings settings) {
        ContentVisibilityEnum visibility = settings == null ? null : ContentVisibilityEnum.fromCode(settings.visibility());
        if (visibility == null
                || settings.allowComment() == null || settings.allowComment() < 0 || settings.allowComment() > 1)
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "可见性或评论设置无效");
        String target = visibility.getCode();

        Content content = access.requireOwner(access.locked(id), viewer);
        if (content.getStatus() != ContentStatusEnum.PUBLISHED) throw new BizException(
                ErrorCode.STATE_CONFLICT,
                "仅可修改已发布内容");
        boolean visibilityChanged = !target.equals(content.getVisibility());

        contents.update(null, new LambdaUpdateWrapper<Content>().eq(Content::getId, id)
                .setSql(visibilityChanged, "index_version = index_version + 1")
                .set(Content::getVisibility, target).set(Content::getAllowComment, settings.allowComment()));

        statistics.changeVisibility(content, visibility);
        if (visibilityChanged)
            rebuild(content);
    }

    @Transactional
    public void delete(Long id, Long viewer) {
        Content content = access.requireOwner(access.locked(id), viewer);

        contents.update(null, new LambdaUpdateWrapper<Content>().eq(Content::getId, id)
                .setSql("index_version = index_version + 1").set(Content::getIsDelete, 1));

        statistics.delete(content);
        rebuild(content);
    }

    private void rebuild(Content content) {
        ContentPublicationEvent event = new ContentPublicationEvent()
                .setContentId(content.getId())
                .setAuthorId(content.getAuthorId())
                .setAudioId(content.getAudioId()).setAudioCountDone(true)
                .setStatus(content.getStatus()).setReason("内容设置变更")
                .setSearchDone(false).setNoticeDone(true).setCompleted(false);
        events.insert(event);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                effects.trigger(event.getId());
            }
        });
    }
}
