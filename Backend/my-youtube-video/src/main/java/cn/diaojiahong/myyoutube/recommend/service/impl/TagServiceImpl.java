package cn.diaojiahong.myyoutube.recommend.service.impl;

import cn.diaojiahong.myyoutube.common.utils.StrUtils;
import cn.diaojiahong.myyoutube.recommend.domain.po.Tag;
import cn.diaojiahong.myyoutube.recommend.mapper.TagMapper;
import cn.diaojiahong.myyoutube.recommend.service.ITagService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <p>
 * 标签表 服务实现类
 * </p>
 *
 * @author thyme
 * @since 2026-03-25
 */
@Service
public class TagServiceImpl extends ServiceImpl<TagMapper, Tag> implements ITagService {

    @Override
    public List<Long> getTagIdsBySlugs(String tags) {
        // 动态等可选标签场景: 空串直接返回空
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        // 拿出所有的标签
        String[] split = tags.split(",");

        // 生成所有的slug
        List<String> slugs = Arrays.stream(split)
                .map(StrUtils::generateSlug)
                .toList();

        // 转换成map
        Map<String, Long> map = lambdaQuery().in(Tag::getSlug, slugs).list().stream()
                .collect(Collectors.toMap(Tag::getSlug, Tag::getId));

        List<Long> list = new ArrayList<>(slugs.size());
        List<Tag> tagList = new ArrayList<>();

        for (int i = 0; i < slugs.size(); i++) {
            if (!map.containsKey(slugs.get(i))) {
                // 不存在需要创建
                Tag tag = new Tag().setName(split[i]).setSlug(slugs.get(i));
                tagList.add(tag);
            } else {
                list.add(map.get(slugs.get(i)));
            }
        }

        // 批量插入
        saveBatch(tagList);

        for (Tag tag : tagList) {
            list.add(tag.getId());
        }

        return list;
    }
}
