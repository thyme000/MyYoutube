package cn.diaojiahong.myyoutube.like.service;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.like.domain.po.ContentLike;
import cn.diaojiahong.myyoutube.like.domain.vo.LikeVideoVO;
import cn.diaojiahong.myyoutube.video.domain.vo.ShortsVO;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 视频点赞表 服务类
 * </p>
 *
 * @author thyme
 * @since 2026-04-01
 */
public interface IContentLikeService extends IService<ContentLike> {

    void likeVideo(Long videoId);
    void cancelLikeVideo(Long videoId);
    void dislikeVideo(Long videoId);
    PageResult<LikeVideoVO> getLikeVideoList(Long userId, PageQuery pageQuery);
    Boolean getLikeStatus(Long videoId);
    void likeComment(Long commentId);
    void cancelLikeComment(Long commentId);}
