package cn.diaojiahong.myyoutube.recommend.service.impl;

import cn.diaojiahong.myyoutube.common.utils.StrUtils;
import cn.diaojiahong.myyoutube.recommend.domain.po.ContentTag;
import cn.diaojiahong.myyoutube.recommend.mapper.ContentTagMapper;
import cn.diaojiahong.myyoutube.recommend.service.ITagService;
import cn.diaojiahong.myyoutube.recommend.service.IContentTagService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * <p>
 * 内容-标签关联表 服务实现类
 * </p>
 *
 * @author thyme
 * @since 2026-03-25
 */
@Service
@RequiredArgsConstructor
public class ContentTagServiceImpl extends ServiceImpl<ContentTagMapper, ContentTag> implements IContentTagService {

    private final ITagService tagService;

    @Override
    @Transactional
    public List<Long> saveTags(String tags, Long contentId) {
        if (contentId == null) {
            throw new IllegalArgumentException("内容id不能为空!");
        }

        // 删除重复的标签关联 (方便后面扩展编辑标签)
        lambdaUpdate().eq(ContentTag::getContentId, contentId).remove();

        // 获取所有的标签id,没有的标签id,则创建新的标签
        List<Long> tagIds = tagService.getTagIdsBySlugs(tags);

        // 批量插入
        if (!tagIds.isEmpty()) {
            saveBatch(tagIds.stream().map(tagId -> new ContentTag().setContentId(contentId).setTagId(tagId)).toList());
        }
        return tagIds;
    }
}
