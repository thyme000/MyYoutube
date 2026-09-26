package cn.diaojiahong.myyoutube.like.service;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.like.domain.po.VideoLike;
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
public interface IVideoLikeService extends IService<VideoLike> {

    Result<Void> likeVideo(Long videoId);

    Result<Void> cancelLikeVideo(Long videoId);

    Result<Void> dislikeVideo(Long videoId);

    Result<PageResult<LikeVideoVO>> getLikeVideoList(Long userId, PageQuery pageQuery);

    Result<Boolean> getLikeStatus(Long videoId);

    Result<Void> likeComment(Long commentId);

    Result<Void> cancelLikeComment(Long commentId);
}
