package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ContentAccessService {
    private final ContentMapper contents;

    public Content readable(Long id, Long viewer) {
        return requireReadable(contents.selectById(id), viewer);
    }
    public Content forReview(Long id) {
        var content = contents.selectById(id);
        if (content == null || !Integer.valueOf(0).equals(content.getIsDelete()))
            throw new BizException(ErrorCode.NOT_FOUND, "内容不存在");
        return content;
    }
    public Content locked(Long id) {
        return contents.selectOne(new LambdaQueryWrapper<Content>().eq(Content::getId, id).last("FOR UPDATE"));
    }
    public Content requireReadable(Content content, Long viewer) {
        if (content == null || !Integer.valueOf(0).equals(content.getIsDelete())
                || !"published".equals(content.getStatus())
                || !("public".equals(content.getVisibility()) || Objects.equals(content.getAuthorId(), viewer)))
            throw new BizException(ErrorCode.NOT_FOUND, "内容不存在或不可访问");
        return content;
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
