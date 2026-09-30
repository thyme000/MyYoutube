package cn.diaojiahong.myyoutube.video.port;

import cn.diaojiahong.myyoutube.follow.domain.po.UserFollow;
import cn.diaojiahong.myyoutube.follow.mapper.UserFollowMapper;
import cn.diaojiahong.myyoutube.like.domain.po.VideoLike;
import cn.diaojiahong.myyoutube.like.mapper.VideoLikeMapper;
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
    private final VideoLikeMapper videoLikeMapper;
    private final UserFollowMapper userFollowMapper;

    @Override
    public List<VideoSummary> listByIdsOrdered(Set<String> ids) {
        List<Video> videos = videoMapper.selectOrderedList(ids);
        return videos.stream().map(v -> {
            VideoSummary s = new VideoSummary();
            s.setId(v.getId());
            s.setTitle(v.getTitle());
            s.setDescription(v.getDescription());
            s.setCoverUrl(v.getCoverUrl());
            s.setVideoUrl(v.getVideoUrl());
            s.setDuration(v.getDuration());
            s.setLikeCount(v.getLikeCount());
            s.setPlayCount(v.getPlayCount());
            s.setShareCount(v.getShareCount());
            s.setCommentCount(v.getCommentCount());
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
        return new LambdaQueryChainWrapper<>(videoLikeMapper)
                .eq(VideoLike::getUserId, userId)
                .eq(VideoLike::getIsLiked, true)
                .in(VideoLike::getVideoId, videoIds)
                .list().stream().collect(Collectors.toMap(VideoLike::getVideoId, v -> true));
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
