package cn.diaojiahong.myyoutube.like.service;

import cn.diaojiahong.myyoutube.content.domain.dto.ContentPageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.like.domain.po.ContentLike;
import cn.diaojiahong.myyoutube.like.domain.vo.LikedContentVO;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 内容点赞表 服务类
 * </p>
 *
 * @author thyme
 * @since 2026-04-01
 */
public interface IContentLikeService extends IService<ContentLike> {

    void likeContent(Long contentId);

    void cancelLikeContent(Long contentId);

    void dislikeContent(Long contentId);

    PageResult<LikedContentVO> getLikedContentList(Long userId, ContentPageQuery pageQuery);

    Boolean getLikeStatus(Long contentId);

    void likeComment(Long commentId);

    void cancelLikeComment(Long commentId);
    }
