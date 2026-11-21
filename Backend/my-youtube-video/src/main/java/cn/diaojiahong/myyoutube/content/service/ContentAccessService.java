package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.user.api.UserBlockPort;
import cn.diaojiahong.myyoutube.video.api.enums.ContentStatusEnum;
import cn.diaojiahong.myyoutube.video.api.enums.ContentVisibilityEnum;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContentAccessService {
    private final ContentMapper contents;
    private final AudioContentAccess audioAccess;
    private final UserBlockPort blocks;

    public Content readable(Long id, Long viewer) {
        return requireReadable(contents.selectById(id), viewer);
    }

    public Set<Long> readableIds(Collection<Long> ids, Long viewer) {
        List<Long> blockedAuthors = viewer == null ? List.of() : blocks.blockedAuthors(viewer);
        List<Content> visible = contents.selectBatchIds(ids).stream()
                .filter(content -> isVisible(content, viewer))
                .filter(content -> !blockedAuthors.contains(content.getAuthorId()))
                .toList();
        Set<Long> blocked = audioAccess.blocked(visible);
        return visible.stream().map(Content::getId)
                .filter(id -> !blocked.contains(id))
                .collect(Collectors.toSet());
    }

    public Content forReview(Long id) {
        Content content = contents.selectById(id);
        if (content == null || !Integer.valueOf(0).equals(content.getIsDelete()))
            throw new BizException(ErrorCode.NOT_FOUND, "内容不存在");
        return content;
    }

    public Content locked(Long id) {
        return contents.selectOne(new LambdaQueryWrapper<Content>().eq(Content::getId, id).last("FOR UPDATE"));
    }

    public Content requireReadable(Content content, Long viewer) {
        requireVisible(content, viewer);
        if (hasBannedAudio(content)) {
            throw notReadable();
        }
        return content;
    }

    Content requireVisible(Content content, Long viewer) {
        if (!isVisible(content, viewer) || viewer != null
                && !Objects.equals(content.getAuthorId(), viewer)
                && blocks.blockedAuthors(viewer).contains(content.getAuthorId())) {
            throw notReadable();
        }

        return content;
    }

    private boolean isVisible(Content content, Long viewer) {
        return content != null && Integer.valueOf(0).equals(content.getIsDelete())
                && content.getStatus() == ContentStatusEnum.PUBLISHED
                && (ContentVisibilityEnum.PUBLIC.getCode().equals(content.getVisibility())
                || Objects.equals(content.getAuthorId(), viewer));
    }

    static BizException notReadable() {
        return new BizException(ErrorCode.NOT_FOUND, "内容不存在或不可访问");
    }

    public boolean hasBannedAudio(Content content) {
        return audioAccess.blocked(List.of(content)).contains(content.getId());
    }

    public Content requireOwner(Content content, Long viewer) {
        if (content == null || !Integer.valueOf(0).equals(content.getIsDelete())
                || !Objects.equals(content.getAuthorId(), viewer))
            throw new BizException(ErrorCode.FORBIDDEN, "无权修改内容");
        return content;
    }

    public void requireCommentable(Content content, Long viewer) {
        requireReadable(content, viewer);
        if (!Integer.valueOf(1).equals(content.getAllowComment()))
            throw new BizException(ErrorCode.FORBIDDEN, "评论已关闭");
    }
}
