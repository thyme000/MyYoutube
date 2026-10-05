package cn.diaojiahong.myyoutube.content.strategy;

import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.domain.vo.ContentFeedVO;
import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 内容类型策略: 摘要转换/列表行加载/卡片填充/评论守卫按 video/dynamic 分派,
 * 将来新增第三种内容形态只需加一个实现类, 不再往各 service 里加平行分支。
 */
public interface ContentTypeStrategy {

    ContentTypeEnum type();

    /** 评论开关守卫, 关闭时抛 IllegalArgumentException */
    void assertCommentAllowed(Long contentId);

    /** 批量取领域实体(isDelete=0), key=contentId; 可见性/状态过滤由调用链语义决定, 这里不做 */
    Map<Long, ?> loadRows(Collection<Long> ids);

    /** 摘要侧按类型批量取并转 ContentSummary(可见性/上架过滤随类型自定), key=contentId */
    Map<Long, ContentSummary> fetchSummaries(Set<Long> ids, Map<Long, Content> contents);

    /**
     * 填卡片 VO 的领域字段(标题/封面/媒体地址, objectKey 就地签名)。
     * includeModeration=true 时补 allowComment/status/visibility(创作中心要展示审核态)。
     */
    void fillFeedVO(ContentFeedVO vo, Content c, Object row, boolean includeModeration, long urlExpireSeconds);

    /** 内容池互动计数填充, 两种类型共用 */
    static void fillCounts(ContentSummary s, Content c) {
        s.setLikeCount(c != null && c.getLikeCount() != null ? c.getLikeCount() : 0L);
        s.setPlayCount(c != null && c.getPlayCount() != null ? c.getPlayCount() : 0L);
        s.setShareCount(c != null && c.getShareCount() != null ? c.getShareCount() : 0L);
        s.setCommentCount(c != null && c.getCommentCount() != null ? c.getCommentCount() : 0L);
    }
}
