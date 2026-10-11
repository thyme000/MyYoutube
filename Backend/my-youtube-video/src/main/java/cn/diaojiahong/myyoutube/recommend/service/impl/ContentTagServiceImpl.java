package cn.diaojiahong.myyoutube.recommend.service.impl;

import cn.diaojiahong.myyoutube.recommend.domain.po.ContentTag;
import cn.diaojiahong.myyoutube.recommend.mapper.ContentTagMapper;
import cn.diaojiahong.myyoutube.recommend.service.IContentTagService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 内容-标签关联表 服务实现类
 * </p>
 *
 * @author thyme
 * @since 2026-03-25
 */
@Service
@lombok.RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class ContentTagServiceImpl extends ServiceImpl<ContentTagMapper, ContentTag> implements IContentTagService {

    private final cn.diaojiahong.myyoutube.recommend.service.ITagService tags;
    private final cn.diaojiahong.myyoutube.recommend.tag.TagPoolHolder pool;

    @Override
    @Transactional
    public List<Long> saveAutoTags(Long contentId, Map<Long, Double> tagWeights) {
        if (contentId == null) {
            throw new IllegalArgumentException("内容id不能为空!");
        }
        var old = lambdaQuery().eq(ContentTag::getContentId, contentId).list().stream().map(ContentTag::getTagId)
                .collect(java.util.stream.Collectors.toSet());
        var current = tagWeights == null ? java.util.Set.<Long>of() : java.util.Set.copyOf(tagWeights.keySet());
        var changed = new java.util.HashSet<>(old); changed.addAll(current);
        var values = changed.isEmpty() ? java.util.List.<cn.diaojiahong.myyoutube.recommend.domain.po.Tag>of() : tags.listByIds(changed);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() {
                        for (var tag : values) {
                            int delta = (current.contains(tag.getId()) ? 1 : 0) - (old.contains(tag.getId()) ? 1 : 0);
                            if (delta != 0) try { pool.incrRefCount(tag, delta); }
                            catch (Exception error) { log.warn("Tag popularity requires reconciliation tagId={}", tag.getId(), error); }
                        }
                    }
                });
        lambdaUpdate().eq(ContentTag::getContentId, contentId).remove();
        if (tagWeights == null || tagWeights.isEmpty()) {
            return List.of();
        }
        List<ContentTag> rows = tagWeights.entrySet().stream()
                .map(e -> new ContentTag()
                        .setContentId(contentId)
                        .setTagId(e.getKey())
                        .setWeight(BigDecimal.valueOf(e.getValue()).setScale(2, java.math.RoundingMode.HALF_UP)))
                .toList();
        saveBatch(rows);
        return List.copyOf(tagWeights.keySet());
    }
}
