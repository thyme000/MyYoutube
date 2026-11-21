package cn.diaojiahong.myyoutube.like.service;

import cn.diaojiahong.myyoutube.content.domain.dto.ContentPageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.like.domain.po.ContentLike;
import cn.diaojiahong.myyoutube.like.domain.vo.LikedContentVO;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IContentLikeService extends IService<ContentLike> {

    void likeContent(Long contentId);

    void cancelLikeContent(Long contentId);

    PageResult<LikedContentVO> getLikedContentList(Long userId, ContentPageQuery pageQuery);

    Boolean getLikeStatus(Long contentId);

    void likeComment(Long commentId);

    void cancelLikeComment(Long commentId);
    }
