package cn.diaojiahong.myyoutube.content.strategy;

import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.domain.vo.ContentFeedVO;
import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.mapper.VideoMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class VideoContentStrategy implements ContentTypeStrategy {

    private final VideoMapper videoMapper;
    private final S3StorageUtil storageUtil;

    public VideoContentStrategy(VideoMapper videoMapper, S3StorageUtil storageUtil) {
        this.videoMapper = videoMapper;
        this.storageUtil = storageUtil;
    }

    @Override
    public ContentTypeEnum type() {
        return ContentTypeEnum.VIDEO;
    }

    @Override
    public void assertCommentAllowed(Long contentId) {
        Video video = videoMapper.selectById(contentId);
        if (video != null && video.getAllowComment() == 0) {
            throw new IllegalArgumentException("视频不允许评论");
        }
    }

    @Override
    public Map<Long, ?> loadRows(Collection<Long> ids) {
        return videoMapper.selectList(new LambdaQueryWrapper<Video>()
                        .in(Video::getId, ids).eq(Video::getIsDelete, 0))
                .stream().collect(Collectors.toMap(Video::getId, Function.identity()));
    }

    @Override
    public Map<Long, ContentSummary> fetchSummaries(Set<Long> ids, Map<Long, Content> contents) {
        // selectOrderedList 自带 public+published 过滤
        Set<String> idKeys = ids.stream().map(String::valueOf).collect(Collectors.toSet());
        return videoMapper.selectOrderedList(idKeys).stream()
                .collect(Collectors.toMap(Video::getId, v -> toSummary(v, contents.get(v.getId()))));
    }

    private ContentSummary toSummary(Video v, Content c) {
        ContentSummary s = new ContentSummary();
        s.setId(v.getId());
        s.setType(type().getCode());
        s.setTitle(v.getTitle());
        s.setDescription(v.getDescription());
        s.setCoverUrl(v.getCoverUrl());
        s.setVideoUrl(v.getVideoUrl());
        s.setDuration(v.getDuration());
        ContentTypeStrategy.fillCounts(s, c);
        s.setAllowComment(v.getAllowComment());
        s.setAuthorId(v.getAuthorId());
        s.setCreateTime(v.getCreateTime());
        s.setTags(v.getTags());
        return s;
    }

    @Override
    public void fillFeedVO(ContentFeedVO vo, Content c, Object row, boolean includeModeration, long urlExpireSeconds) {
        Video v = (Video) row;
        vo.setTitle(v.getTitle());
        vo.setDescription(v.getDescription());
        vo.setCoverUrl(storageUtil.signGetUrl(v.getCoverUrl(), urlExpireSeconds));
        vo.setVideoUrl(storageUtil.signGetUrl(v.getVideoUrl(), urlExpireSeconds));
        vo.setDuration(v.getDuration());
        if (includeModeration) {
            vo.setAllowComment(v.getAllowComment());
            vo.setStatus(v.getStatus() != null ? v.getStatus().getCode() : null);
        }
        vo.setVisibility(v.getVisibility() != null ? v.getVisibility().getCode() : null);
    }
}
