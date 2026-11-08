package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.content.cache.ContentResourceWarmupProperties;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.user.api.FollowPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台任务刻意绕过 CONTENT_STATE 缓存，重新查库检查发布后的可访问性。
 * 审核快照只用于理解产物预热，资格检查使用当前内容和音频状态。
 */
@Service
@RequiredArgsConstructor
public class ContentResourceWarmupLoader {
    private final ContentAccessService access;
    private final FollowPort followPort;
    private final ContentResourceWarmupProperties properties;

    @Transactional(readOnly = true, timeout = 5, propagation = Propagation.REQUIRES_NEW)
    public Content loadReadable(Long id) {
        // 无查看者身份，复用公开访问及关联音频检查，私密内容不会按作者权限放行。
        return access.readable(id, null);
    }

    /** 粉丝数走远程 FollowPort, 刻意放在事务外, 不让 HTTP 调用拖进 DB 连接持有期 */
    public boolean aboveFanThreshold(Content content) {
        Long fans = followPort.fansCount(content.getAuthorId());
        long count = fans == null ? 0 : fans;
        return count >= properties.getMinFans();
    }
}
