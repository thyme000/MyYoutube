package cn.diaojiahong.myyoutube.share.service.impl;

import cn.diaojiahong.myyoutube.common.counter.AbstractCounterTarget;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.ContentAccessService;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.share.domain.po.ContentShare;
import cn.diaojiahong.myyoutube.share.mapper.ContentShareMapper;
import cn.diaojiahong.myyoutube.share.service.IContentShareService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 分享数是低频动作, 直更即终态: 事实行和计数同事务, 结构上不会漂移, 无需对账
 */
@Service
@RequiredArgsConstructor
public class ContentShareServiceImpl implements IContentShareService {

    private final ContentShareMapper shares;
    private final IContentService contentService;
    private final ContentAccessService access;

    @Override
    @Transactional
    public void record(Long contentId, Long userId) {
        access.readable(contentId, userId);
        shares.insert(new ContentShare().setUserId(userId).setContentId(contentId).setCreateTime(LocalDateTime.now()));
        AbstractCounterTarget.guardedDelta(contentService, Content::getId, contentId, Content::getShareCount, 1);
    }
}
