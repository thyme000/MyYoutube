package cn.diaojiahong.myyoutube.video.port;

import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.video.api.ContentSummaryPort;
import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.domain.po.Dynamic;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.mapper.DynamicMapper;
import cn.diaojiahong.myyoutube.video.mapper.VideoMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ContentSummaryPortImpl implements ContentSummaryPort {

    private final ContentMapper contentMapper;
    private final VideoMapper videoMapper;
    private final DynamicMapper dynamicMapper;
    private final ObjectMapper objectMapper;

    @Override
    public List<ContentSummary> listByIdsOrdered(Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Long> idList = ids.stream().map(Long::valueOf).toList();
        Map<Long, Content> contents = contentMapper.selectBatchIds(idList).stream()
                .collect(Collectors.toMap(Content::getId, c -> c));

        Set<String> videoIds = new HashSet<>();
        Set<String> dynamicIds = new HashSet<>();
        for (String id : ids) {
            Content c = contents.get(Long.valueOf(id));
            if (c == null) {
                continue;
            }
            if (c.getType() == ContentTypeEnum.VIDEO) {
                videoIds.add(id);
            } else if (c.getType() == ContentTypeEnum.DYNAMIC) {
                dynamicIds.add(id);
            }
        }

        Map<Long, ContentSummary> summaryMap = new LinkedHashMap<>();
        if (!videoIds.isEmpty()) {
            // selectOrderedList 自带 public+published 过滤
            videoMapper.selectOrderedList(videoIds)
                    .forEach(v -> summaryMap.put(v.getId(), toVideoSummary(v, contents.get(v.getId()))));
        }
        if (!dynamicIds.isEmpty()) {
            dynamicMapper.selectList(Wrappers.<Dynamic>lambdaQuery()
                            .in(Dynamic::getId, dynamicIds.stream().map(Long::valueOf).toList())
                            .eq(Dynamic::getIsDelete, 0))
                    .forEach(d -> summaryMap.put(d.getId(), toDynamicSummary(d, contents.get(d.getId()))));
        }

        List<ContentSummary> ordered = new ArrayList<>(ids.size());
        for (String id : ids) {
            ContentSummary s = summaryMap.get(Long.valueOf(id));
            if (s != null) {
                ordered.add(s);
            }
        }
        return ordered;
    }

    private ContentSummary toVideoSummary(Video v, Content c) {
        ContentSummary s = new ContentSummary();
        s.setId(v.getId());
        s.setType(ContentTypeEnum.VIDEO.getCode());
        s.setTitle(v.getTitle());
        s.setDescription(v.getDescription());
        s.setCoverUrl(v.getCoverUrl());
        s.setVideoUrl(v.getVideoUrl());
        s.setDuration(v.getDuration());
        fillCounts(s, c);
        s.setAllowComment(v.getAllowComment());
        s.setAuthorId(v.getAuthorId());
        s.setCreateTime(v.getCreateTime());
        s.setTags(v.getTags());
        return s;
    }

    private ContentSummary toDynamicSummary(Dynamic d, Content c) {
        ContentSummary s = new ContentSummary();
        s.setId(d.getId());
        s.setType(ContentTypeEnum.DYNAMIC.getCode());
        s.setTitle(d.getTitle());
        s.setCoverUrl(firstFrameKey(d.getTimelineJson()));
        s.setTotalDurationMs(d.getTotalDurationMs());
        fillCounts(s, c);
        s.setAllowComment(1);
        s.setAuthorId(d.getUserId());
        s.setCreateTime(d.getCreateTime());
        s.setTags(d.getTags());
        return s;
    }

    private void fillCounts(ContentSummary s, Content c) {
        s.setLikeCount(c != null && c.getLikeCount() != null ? c.getLikeCount() : 0L);
        s.setPlayCount(c != null && c.getPlayCount() != null ? c.getPlayCount() : 0L);
        s.setShareCount(c != null && c.getShareCount() != null ? c.getShareCount() : 0L);
        s.setCommentCount(c != null && c.getCommentCount() != null ? c.getCommentCount() : 0L);
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
            // 动态时间轴解析失败时封面留空, 不影响摘要返回
        }
        return null;
    }
}
