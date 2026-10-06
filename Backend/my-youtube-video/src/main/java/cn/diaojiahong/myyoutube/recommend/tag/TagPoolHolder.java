package cn.diaojiahong.myyoutube.recommend.tag;

import cn.diaojiahong.myyoutube.recommend.domain.po.Tag;
import cn.diaojiahong.myyoutube.recommend.service.ITagService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 标签词表池: 机器打标的受控候选集, 全量启用标签按类目分组缓存
 */
@Component
@RequiredArgsConstructor
public class TagPoolHolder {

    private final ITagService tagService;

    /** category_code → 词库, key 为空串表示通用池 */
    private volatile Map<String, List<Tag>> poolByCategory = Map.of();
    private volatile Map<String, Tag> tagBySlug = Map.of();

    @Scheduled(initialDelay = 10_000L, fixedDelay = 600_000L)
    public void refresh() {
        List<Tag> tags = tagService.lambdaQuery().eq(Tag::getStatus, 1).list();
        this.poolByCategory = tags.stream().collect(Collectors.groupingBy(
                t -> t.getCategoryCode() == null ? "" : t.getCategoryCode()));
        this.tagBySlug = tags.stream()
                .collect(Collectors.toMap(Tag::getSlug, t -> t, (a, b) -> a));
    }

    /** 指定类目的词库 + 通用词库 */
    public List<Tag> byCategory(String categoryCode) {
        List<Tag> result = new ArrayList<>();
        List<Tag> specific = poolByCategory.get(categoryCode == null ? "" : categoryCode);
        if (specific != null) {
            result.addAll(specific);
        }
        List<Tag> common = poolByCategory.get("");
        if (common != null) {
            result.addAll(common);
        }
        return result;
    }

    public Tag findBySlug(String slug) {
        return tagBySlug.get(slug);
    }

    /** category_code → 词库视图, key 为空串表示通用池 */
    public Map<String, List<Tag>> grouped() {
        return poolByCategory;
    }

    public Collection<Tag> all() {
        return tagBySlug.values();
    }
}
