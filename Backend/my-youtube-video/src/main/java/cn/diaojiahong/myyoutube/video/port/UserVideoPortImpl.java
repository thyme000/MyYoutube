package cn.diaojiahong.myyoutube.video.port;

import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.follow.domain.po.UserFollow;
import cn.diaojiahong.myyoutube.follow.mapper.UserFollowMapper;
import cn.diaojiahong.myyoutube.like.domain.po.ContentLike;
import cn.diaojiahong.myyoutube.like.mapper.ContentLikeMapper;
import cn.diaojiahong.myyoutube.video.api.UserVideoPort;
import cn.diaojiahong.myyoutube.video.api.dto.VideoSummary;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.mapper.VideoMapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class UserVideoPortImpl implements UserVideoPort {

    private final VideoMapper videoMapper;
    private final ContentLikeMapper contentLikeMapper;
    private final IContentService contentService;
    private final UserFollowMapper userFollowMapper;

    @Override
    public List<VideoSummary> listByIdsOrdered(Set<String> ids) {
        List<Video> videos = videoMapper.selectOrderedList(ids);
        if (videos.isEmpty()) {
            return List.of();
        }
        Map<Long, Content> contents = contentService.listByIds(
                        videos.stream().map(Video::getId).toList()).stream()
                .collect(Collectors.toMap(Content::getId, c -> c));
        return videos.stream().map(v -> {
            Content c = contents.get(v.getId());
            VideoSummary s = new VideoSummary();
            s.setId(v.getId());
            s.setTitle(v.getTitle());
            s.setDescription(v.getDescription());
            s.setCoverUrl(v.getCoverUrl());
            s.setVideoUrl(v.getVideoUrl());
            s.setDuration(v.getDuration());
            s.setLikeCount(c != null ? c.getLikeCount() : 0L);
            s.setPlayCount(c != null ? c.getPlayCount() : 0L);
            s.setShareCount(c != null ? c.getShareCount() : 0L);
            s.setCommentCount(c != null ? c.getCommentCount() : 0L);
            s.setAllowComment(v.getAllowComment());
            s.setAuthorId(v.getAuthorId());
            s.setCreateTime(v.getCreateTime());
            s.setTags(v.getTags());
            return s;
        }).collect(Collectors.toList());
    }

    @Override
    public Map<Long, Boolean> likedMap(Long userId, Set<String> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) {
            return Map.of();
        }
        return new LambdaQueryChainWrapper<>(contentLikeMapper)
                .eq(ContentLike::getUserId, userId)
                .eq(ContentLike::getIsLiked, true)
                .in(ContentLike::getContentId, videoIds)
                .list().stream().collect(Collectors.toMap(ContentLike::getContentId, v -> true));
    }

    @Override
    public Map<Long, Boolean> followedMap(Long userId, Collection<Long> authorIds) {
        if (authorIds == null || authorIds.isEmpty()) {
            return Map.of();
        }
        List<UserFollow> list = new LambdaQueryChainWrapper<>(userFollowMapper)
                .eq(UserFollow::getFollowerId, userId)
                .eq(UserFollow::getIsDeleted, false)
                .in(UserFollow::getFolloweeId, authorIds)
                .list();
        return authorIds.stream().collect(Collectors.toMap(id -> id,
                id -> list.stream().anyMatch(f -> f.getFolloweeId().equals(id))));
    }
}
