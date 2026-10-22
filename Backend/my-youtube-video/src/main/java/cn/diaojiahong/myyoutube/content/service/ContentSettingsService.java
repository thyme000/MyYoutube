package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
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
    public record Settings(String visibility, Integer allowComment) {}

    @Transactional
    public void update(Long id, Long viewer, Settings settings) {
        if (settings == null || !("public".equals(settings.visibility()) || "private".equals(settings.visibility()))
                || settings.allowComment() == null || settings.allowComment() < 0 || settings.allowComment() > 1)
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "可见性或评论设置无效");
        var content = access.requireOwner(access.locked(id), viewer);
        if (!"published".equals(content.getStatus())) throw new BizException(ErrorCode.STATE_CONFLICT, "仅可修改已发布内容");
        contents.update(null, new LambdaUpdateWrapper<Content>().eq(Content::getId, id)
                .set(Content::getVisibility, settings.visibility()).set(Content::getAllowComment, settings.allowComment()));
        if (!content.getVisibility().equals(settings.visibility())) rebuild(content, 0);
    }
    @Transactional
    public void delete(Long id, Long viewer) {
        var content = access.requireOwner(access.locked(id), viewer);
        contents.update(null, new LambdaUpdateWrapper<Content>().eq(Content::getId, id).set(Content::getIsDelete, 1));
        rebuild(content, "published".equals(content.getStatus()) ? -1 : 0);
    }
    private void rebuild(Content content, int delta) {
        var event = new ContentPublicationEvent().setContentId(content.getId()).setAuthorId(content.getAuthorId())
                .setStatus(content.getStatus()).setDelta(delta).setReason("内容设置变更")
                .setUserCountDone(delta == 0).setSearchDone(false).setNoticeDone(true).setCompleted(false);
        events.insert(event);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { effects.trigger(event.getId()); }
        });
    }
}
