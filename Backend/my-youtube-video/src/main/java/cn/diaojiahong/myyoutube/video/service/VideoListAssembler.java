package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.like.domain.po.ContentLike;
import cn.diaojiahong.myyoutube.like.mapper.ContentLikeMapper;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.domain.vo.VideoListVO;
import cn.diaojiahong.myyoutube.video.domain.vo.VideoVoConverter;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 列表填充: 计数拷贝、作者批量投影、url 签名、点赞状态, 供各查询方法共用。
 */
@Component
@RequiredArgsConstructor
public class VideoListAssembler {

    private final UserPort userPort;
    private final S3StorageUtil storageUtil;
    private final ContentLikeMapper contentLikeMapper;
    private final IContentService contentService;

    public static final long STORAGE_READ_SIGN_EXPIRE_SECONDS = 24 * 60 * 60;

    public Map<Long, Content> loadContents(List<Video> videos) {

        List<Long> ids = videos.stream().map(Video::getId).toList();
        if (ids.isEmpty()) {

            return Map.of();
        }
        return contentService.listByIds(ids).stream()
                .collect(Collectors.toMap(Content::getId, c -> c));
    }

    public List<VideoListVO> toListVosWithCounts(List<Video> videos) {

        Map<Long, Content> contents = loadContents(videos);
        return videos.stream()
                .map(v -> VideoVoConverter.toListVo(v, contents.get(v.getId())))
                .collect(Collectors.toList());
    }

    /**
     * 批量填充作者昵称与头像: 同一批 authorId 只做一次远程查询, 本地投影, 避免昵称/头像各拉一遍。
     */
    public <T> void fillAuthors(List<T> list, Function<T, Long> idGetter,
                                BiConsumer<T, String> nickSetter, BiConsumer<T, String> avatarSetter) {

        if (list == null || list.isEmpty()) {

            return;
        }
        List<Long> ids = list.stream().map(idGetter).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {

            return;
        }
        Map<Long, UserSummary> users = userPort.listByIds(ids).stream()
                .filter(u -> u.getId() != null)
                .collect(Collectors.toMap(UserSummary::getId, u -> u, (a, b) -> a));
        for (T vo : list) {

            UserSummary u = idGetter.apply(vo) == null ? null : users.get(idGetter.apply(vo));
            if (u == null) {

                continue;
            }
            if (u.getNickname() != null && !u.getNickname().isBlank()) {

                nickSetter.accept(vo, u.getNickname());
            }
            avatarSetter.accept(vo, storageUtil.signGetUrl(u.getAvatarUrl(), STORAGE_READ_SIGN_EXPIRE_SECONDS));
        }
    }

    public <T> void signUrls(List<T> list, Function<T, String> getter, BiConsumer<T, String> setter) {

        if (list == null || list.isEmpty()) {

            return;
        }
        for (T vo : list) {

            setter.accept(vo, storageUtil.signGetUrl(getter.apply(vo), STORAGE_READ_SIGN_EXPIRE_SECONDS));
        }
    }

    public Map<Long, Boolean> likedMap(Long userId, List<Long> contentIds) {

        if (contentIds == null || contentIds.isEmpty()) {

            return Collections.emptyMap();
        }
        List<ContentLike> list = new LambdaQueryChainWrapper<>(contentLikeMapper)
                .eq(ContentLike::getIsLiked, 1)
                .eq(ContentLike::getUserId, userId)
                .in(ContentLike::getContentId, contentIds).list();
        if (list == null || list.isEmpty()) {

            return Collections.emptyMap();
        }
        return list.stream().collect(Collectors.toMap(ContentLike::getContentId, v -> true));
    }
}
