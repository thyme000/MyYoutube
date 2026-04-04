package cn.diaojiahong.myyoutube.comment.service.impl;

import cn.diaojiahong.myyoutube.comment.domain.dto.CommentDTO;
import cn.diaojiahong.myyoutube.comment.domain.dto.CommentPageQuery;
import cn.diaojiahong.myyoutube.comment.domain.enums.CommentStatusEnum;
import cn.diaojiahong.myyoutube.comment.domain.po.VideoComment;
import cn.diaojiahong.myyoutube.comment.domain.vo.CommentVO;
import cn.diaojiahong.myyoutube.comment.mapper.VideoCommentMapper;
import cn.diaojiahong.myyoutube.comment.service.IVideoCommentService;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.AliOssUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.user.domain.dto.UserDTO;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.service.IVideoService;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * <p>
 * 视频评论表 服务实现类
 * </p>
 *
 * @author thyme
 * @since 2026-03-22
 */
@Service
@RequiredArgsConstructor
public class VideoCommentServiceImpl extends ServiceImpl<VideoCommentMapper, VideoComment> implements IVideoCommentService {

    private final IUserService userService;
    private final AliOssUtil aliOssUtil;
    private final IVideoService videoService;
    private final static int OSS_EXPIRE = 60 * 20;

    @Transactional
    @Override
    public Result<Void> publishComment(CommentDTO commentDTO) {
        Long parentId = commentDTO.getParentId();
        if (parentId == null) {
            throw new IllegalArgumentException("父评论id不能为空");
        }
        Video video = videoService.getById(commentDTO.getVideoId());
        if (video == null) {
            throw new IllegalArgumentException("视频不存在");
        } else if (video.getAllowComment() == 0) {
            throw new IllegalArgumentException("视频不允许评论");
        }
        Long userId = getUserId();
        VideoComment videoComment = new VideoComment();
        videoComment.setUserId(userId);
        videoComment.setVideoId(commentDTO.getVideoId());
        videoComment.setContent(commentDTO.getContent());
        videoComment.setParentId(parentId);
        // 判断是否是一级评论
        if (parentId == 0) {
            // 一级评论 更新视频的评论数
            videoService.lambdaUpdate().eq(Video::getId, commentDTO.getVideoId())
                    .setIncrBy(Video::getCommentCount, 1).update();
            videoComment.setRootId(null);
        } else {
            // 父评论
            VideoComment parent = getById(parentId);
            if (parent == null || !CommentStatusEnum.VISIBLE.getCode().equals(parent.getStatus())) {
                throw new IllegalArgumentException("父评论不存在或不可回复");
            }
            if (!Objects.equals(parent.getVideoId(), commentDTO.getVideoId())) {
                throw new IllegalArgumentException("父评论不属于该视频");
            }
            videoComment.setReplyUserId(parent.getUserId());

            // 找根评论id
            if (parent.getRootId() == null) {
                // 父评论是一级评论
                videoComment.setRootId(parent.getId());
                // 更新评论数
                parent.setReplyCount(parent.getReplyCount() + 1);
                updateById(parent);
            } else {
                // 父评论不是一级评论
                videoComment.setRootId(parent.getRootId());
                // 不是一级评论,更新根评论的回复数
                VideoComment root = getById(parent.getRootId());
                root.setReplyCount(root.getReplyCount() + 1);
                updateById(root);
            }
        }
        saveOrUpdate(videoComment);
        return Result.success();
    }

    @Transactional
    @Override
    public Result<Void> deleteComment(Long commentId) {
        VideoComment videoComment = getById(commentId);
        if (videoComment == null) {
            throw new IllegalArgumentException("评论不存在");
        }
        Long userId = getUserId();
        if (!videoComment.getUserId().equals(userId)) {
            throw new IllegalArgumentException("用户没有权限删除评论");
        }
        videoComment.setStatus(CommentStatusEnum.DELETED.getCode());
        updateById(videoComment);
        if (videoComment.getParentId() == 0) {
            // 一级评论更新视频评论数
            videoService.lambdaUpdate().eq(Video::getId, videoComment.getVideoId())
                    .setIncrBy(Video::getCommentCount, -1).update();
            // 删除所有回复评论
            lambdaUpdate()
                    .eq(VideoComment::getRootId, commentId)
                    .set(VideoComment::getStatus, CommentStatusEnum.DELETED.getCode())
                    .update();
        } else {
            // 更新回复数
            lambdaUpdate().eq(VideoComment::getId, videoComment.getRootId())
                    .gt(VideoComment::getReplyCount, 0)
                    .setIncrBy(VideoComment::getReplyCount, -1).update();
        }
        return Result.success();
    }

    @Override
    public Result<PageResult<CommentVO>> getCommentList(CommentPageQuery commentPageQuery) {
        // 视频评论 -> 根据点赞量排序(一样按创建时间排序)
        Page<VideoComment> page = lambdaQuery()
                .eq(VideoComment::getVideoId, commentPageQuery.getVideoId())
                .eq(VideoComment::getStatus, CommentStatusEnum.VISIBLE.getCode())
                .eq(VideoComment::getRootId, commentPageQuery.getParentId() == 0 ? null : commentPageQuery.getParentId())
                .orderByDesc(VideoComment::getLikeCount)
                .orderByDesc(VideoComment::getReplyCount)
                .orderByDesc(VideoComment::getCreateTime)
                .page(commentPageQuery.toPage());
        List<VideoComment> records = page.getRecords();
        // 转vo
        List<CommentVO> commentVOList = records.stream()
                .map(record -> BeanUtil.toBean(record, CommentVO.class)).toList();
        // 填充用户昵称
        // 填充用户头像url
        List<Long> userIds = commentVOList.stream().map(CommentVO::getUserId).toList();
        Map<Long, String> nicknames = userService.getAuthorNicknames(userIds);
        Map<Long, String> avatarUrls = userService.getAuthorAvatarUrls(userIds);
        if (nicknames == null || nicknames.isEmpty() || avatarUrls == null || avatarUrls.isEmpty()) {
            return Result.success(PageResult.of(page.getTotal(), page.getPages(), commentVOList));
        }
        commentVOList.forEach(commentVO -> commentVO.setNickname(nicknames.get(commentVO.getUserId())));
        commentVOList.forEach(commentVO -> commentVO.setAvatarUrl(aliOssUtil.signGetUrl(avatarUrls.get(commentVO.getUserId()), OSS_EXPIRE)));

        // 填充回复用户的昵称
        List<Long> replyUserIds = commentVOList.stream().map(CommentVO::getReplyUserId).toList();
        Map<Long, String> replyNicknames = userService.getAuthorNicknames(replyUserIds);
        if (replyNicknames != null && !replyNicknames.isEmpty()) {
            commentVOList.forEach(commentVO -> commentVO.setReplyNickname(replyNicknames.get(commentVO.getReplyUserId())));
        }

        return Result.success(PageResult.of(page.getTotal(), page.getPages(), commentVOList));
    }

    private Long getUserId() {
        UserDTO user = UserContext.getUser();
        if (user == null) {
            throw new IllegalArgumentException("用户未登录");
        }
        return user.getId();
    }

}
