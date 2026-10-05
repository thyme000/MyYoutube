package cn.diaojiahong.myyoutube.content.strategy;

import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.domain.vo.ContentFeedVO;
import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.domain.po.Dynamic;
import cn.diaojiahong.myyoutube.video.mapper.DynamicMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class DynamicContentStrategy implements ContentTypeStrategy {

    private final DynamicMapper dynamicMapper;
    private final S3StorageUtil storageUtil;
    private final ObjectMapper objectMapper;

    public DynamicContentStrategy(DynamicMapper dynamicMapper, S3StorageUtil storageUtil, ObjectMapper objectMapper) {
        this.dynamicMapper = dynamicMapper;
        this.storageUtil = storageUtil;
        this.objectMapper = objectMapper;
    }

    @Override
    public ContentTypeEnum type() {
        return ContentTypeEnum.DYNAMIC;
    }

    @Override
    public void assertCommentAllowed(Long contentId) {
        Dynamic dynamic = dynamicMapper.selectById(contentId);
        if (dynamic != null && dynamic.getAllowComment() != null && dynamic.getAllowComment() == 0) {
            throw new IllegalArgumentException("动态不允许评论");
        }
    }

    @Override
    public Map<Long, ?> loadRows(Collection<Long> ids) {
        return dynamicMapper.selectList(new LambdaQueryWrapper<Dynamic>()
                        .in(Dynamic::getId, ids).eq(Dynamic::getIsDelete, 0))
                .stream().collect(Collectors.toMap(Dynamic::getId, Function.identity()));
    }

    @Override
    public Map<Long, ContentSummary> fetchSummaries(Set<Long> ids, Map<Long, Content> contents) {
        return dynamicMapper.selectList(new LambdaQueryWrapper<Dynamic>()
                        .in(Dynamic::getId, ids)
                        .eq(Dynamic::getIsDelete, 0)
                        .eq(Dynamic::getVisibility, "public"))
                .stream().collect(Collectors.toMap(Dynamic::getId, d -> toSummary(d, contents.get(d.getId()))));
    }

    private ContentSummary toSummary(Dynamic d, Content c) {
        ContentSummary s = new ContentSummary();
        s.setId(d.getId());
        s.setType(type().getCode());
        s.setTitle(d.getTitle());
        s.setCoverUrl(firstFrameKey(d.getTimelineJson()));
        s.setTotalDurationMs(d.getTotalDurationMs());
        ContentTypeStrategy.fillCounts(s, c);
        s.setAllowComment(1);
        s.setAuthorId(d.getUserId());
        s.setCreateTime(d.getCreateTime());
        s.setTags(d.getTags());
        return s;
    }

    @Override
    public void fillFeedVO(ContentFeedVO vo, Content c, Object row, boolean includeModeration, long urlExpireSeconds) {
        Dynamic d = (Dynamic) row;
        vo.setTitle(d.getTitle());
        vo.setCoverUrl(storageUtil.signGetUrl(firstFrameKey(d.getTimelineJson()), urlExpireSeconds));
        vo.setTotalDurationMs(d.getTotalDurationMs());
        if (includeModeration) {
            vo.setAllowComment(d.getAllowComment() != null ? d.getAllowComment() : 1);
            vo.setStatus(c.getStatus());
        }
        vo.setVisibility(d.getVisibility() != null ? d.getVisibility() : "public");
    }

    /** 从时间轴 JSON 里取首帧图片 objectKey 作封面 */
    private String firstFrameKey(String timelineJson) {
        if (timelineJson == null) {
            return null;
        }
        try {
            List<Map<String, Object>> timeline = objectMapper.readValue(timelineJson,
                    new TypeReference<List<Map<String, Object>>>() {});
            if (!timeline.isEmpty() && timeline.get(0).get("imageObjectKey") != null) {
                return String.valueOf(timeline.get(0).get("imageObjectKey"));
            }
        } catch (Exception ignored) {
            // 时间轴解析失败时封面留空
        }
        return null;
    }
}
