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
public class ContentTagServiceImpl extends ServiceImpl<ContentTagMapper, ContentTag> implements IContentTagService {

    @Override
    @Transactional
    public List<Long> saveAutoTags(Long contentId, Map<Long, Double> tagWeights) {
        if (contentId == null) {
            throw new IllegalArgumentException("内容id不能为空!");
        }
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
