package cn.diaojiahong.myyoutube.admin.service.impl;

import cn.diaojiahong.myyoutube.admin.domain.dto.AdminVideoPageQueryDTO;
import cn.diaojiahong.myyoutube.admin.domain.vo.AdminVideoVO;
import cn.diaojiahong.myyoutube.admin.service.IAdminService;
import cn.diaojiahong.myyoutube.comment.domain.enums.CommentStatusEnum;
import cn.diaojiahong.myyoutube.comment.domain.po.VideoComment;
import cn.diaojiahong.myyoutube.comment.service.IVideoCommentService;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.AliOssUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.recommend.domain.doc.VideoDocRepository;
import cn.diaojiahong.myyoutube.search.domain.doc.UserDocRepository;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import cn.diaojiahong.myyoutube.video.domain.enums.VideoStatusEnum;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.service.IVideoService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.data.elasticsearch.core.suggest.Completion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static cn.diaojiahong.myyoutube.common.exception.BizException.USER_ERROR;
import static cn.diaojiahong.myyoutube.video.domain.enums.VideoStatusEnum.BANNED;
import static cn.diaojiahong.myyoutube.video.domain.enums.VideoStatusEnum.PUBLISHED;

@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements IAdminService {

    private final IVideoService videoService;
    private final IUserService userService;
    private final VideoDocRepository videoDocRepository;
    private final UserDocRepository userDocRepository;
    private final IVideoCommentService videoCommentService;
    private final AliOssUtil aliOssUtil;

    @Transactional
    @Override
    public Result<String> pass(Long videoId) {
        Long userId = getUserId();
        // 判断是否时管理员
        if (!isAdmin(userId)) {
            throw new BizException(USER_ERROR, "用户不是管理员");
        }
        boolean success = videoService.lambdaUpdate()
                .set(Video::getStatus, PUBLISHED.getCode())
                .eq(Video::getId, videoId)
                .update();
        if (!success) {
            return Result.fail("发布失败");
        }
        // 发布成功, 作者作品数量加一
        Video video = videoService.lambdaQuery().eq(Video::getId, videoId).one();
        userService.lambdaUpdate()
                .eq(User::getId, video.getAuthorId())
                .setIncrBy(User::getVideoCount, 1)
                .update();
        // 修改es中的视频状态
        videoDocRepository.findById(videoId).ifPresent(doc -> {
            List<String> list = new ArrayList<>(List.of(
                    video.getTitle(),
                    video.getDescription(),
                    doc.getAuthorName()
            ));
            list.addAll(Arrays.asList(video.getTags().split(",")));
            doc.setStatus(PUBLISHED.getCode());
            doc.setSuggest(new Completion(list));
            videoDocRepository.save(doc);
        });
        // 修改es中用户的作品数量
        userDocRepository.findById(video.getAuthorId()).ifPresent(
                u -> {
                    u.setVideoCount(u.getVideoCount() + 1);
                    userDocRepository.save(u);
                }
        );
        return Result.success("发布成功");
    }

    @Override
    public Result<String> ban(Long videoId) {
        Long userId = getUserId();
        // 判断是否时管理员
        if (!isAdmin(userId)) {
            throw new BizException(USER_ERROR, "用户不是管理员");
        }
        Video video = videoService.lambdaQuery()
                .eq(Video::getId, videoId)
                .one();
        if (video == null) {
            return Result.fail("视频不存在");
        }
        VideoStatusEnum oldStatus = video.getStatus();
        video.setStatus(BANNED);
        videoService.updateById(video);
        if (oldStatus == PUBLISHED) {
            // 禁用成功, 作者作品数量减一
            userService.lambdaUpdate()
                    .eq(User::getId, video.getAuthorId())
                    .setDecrBy(User::getVideoCount, 1)
                    .update();
            // 修改es中用户的作品数量
            userDocRepository.findById(video.getAuthorId()).ifPresent(
                    u -> {
                        u.setVideoCount(u.getVideoCount() - 1);
                        userDocRepository.save(u);
                    }
            );
        }
        // 修改es中的视频状态
        videoDocRepository.findById(videoId).ifPresent(doc -> {
            doc.setStatus(BANNED.getCode());
            doc.setSuggest(new Completion());
            videoDocRepository.save(doc);
        });
        return Result.success("禁用成功");
    }

    @Override
    public Result<PageResult<AdminVideoVO>> getVideosPage(AdminVideoPageQueryDTO adminVideoPageQueryDTO) {
        Long userId = getUserId();
        // 判断是否时管理员
        if (!isAdmin(userId)) {
            throw new BizException(USER_ERROR, "用户不是管理员");
        }
        Page<Video> page = videoService.lambdaQuery()
                .eq(adminVideoPageQueryDTO.getVideoStatus() != null, Video::getStatus, adminVideoPageQueryDTO.getVideoStatus())
                .eq(adminVideoPageQueryDTO.getUserId() != null, Video::getAuthorId, adminVideoPageQueryDTO.getUserId())
                .eq(adminVideoPageQueryDTO.getVideoType() != null, Video::getVideoType, adminVideoPageQueryDTO.getVideoType())
                .eq(adminVideoPageQueryDTO.getVideoCategory() != null, Video::getCategory, adminVideoPageQueryDTO.getVideoCategory())
                .page(adminVideoPageQueryDTO.toPage());
        if (page == null || page.getRecords().isEmpty()) {
            return Result.success(PageResult.of(0, 0, new ArrayList<>()));
        }
        // 转vo
        List<Video> videoList = page.getRecords();
        List<Long> list = videoList.stream().map(Video::getAuthorId).toList();
        Map<Long, String> authorAvatarUrls = userService.getAuthorAvatarUrls(list);
        Map<Long, String> authorNicknames = userService.getAuthorNicknames(list);
        // 填充
        List<AdminVideoVO> voList = new ArrayList<>();
        videoList.forEach(video -> {
            AdminVideoVO vo = new AdminVideoVO();
            vo.setVideoId(video.getId());
            vo.setTitle(video.getTitle());
            vo.setDescription(video.getDescription());
            vo.setCoverUrl(aliOssUtil.signGetUrl(video.getCoverUrl(), 60 * 10));
            vo.setVideoUrl(aliOssUtil.signGetUrl(video.getVideoUrl(), 60 * 10));
            vo.setVideoType(video.getVideoType());
            vo.setVideoCategory(video.getCategory());
            vo.setCreateTime(video.getCreateTime());
            vo.setAuthorId(video.getAuthorId());
            vo.setAuthorNickname(authorNicknames.get(video.getAuthorId()));
            vo.setAuthorAvatarUrl(aliOssUtil.signGetUrl(authorAvatarUrls.get(video.getAuthorId()), 60 * 10));
            voList.add(vo);
        });
        return Result.success(PageResult.of(page.getTotal(), page.getSize(), voList));
    }

    @Override
    public Result<Void> deleteComment(Long commentId) {
        Long userId = getUserId();
        // 判断是否时管理员
        if (!isAdmin(userId)) {
            throw new BizException(USER_ERROR, "用户不是管理员");
        }
        VideoComment comment = videoCommentService.getById(commentId);
        if (comment == null || comment.getStatus().equals(CommentStatusEnum.DELETED.getCode())
                || comment.getStatus().equals(CommentStatusEnum.BLOCKED.getCode())) {
            return Result.success();
        }
        if (comment.getParentId() == 0) {
            // 删除所有回复评论
            videoCommentService.lambdaUpdate()
                    .eq(VideoComment::getRootId, commentId)
                    .set(VideoComment::getStatus, CommentStatusEnum.BLOCKED.getCode())
                    .update();
        } else {
            // 更新回复数
            videoCommentService.lambdaUpdate().eq(VideoComment::getId, comment.getRootId())
                    .gt(VideoComment::getReplyCount, 0)
                    .setIncrBy(VideoComment::getReplyCount, -1).update();
        }
        // 更新视频评论数
        videoService.lambdaUpdate().eq(Video::getId, comment.getVideoId())
                .setIncrBy(Video::getCommentCount, -1).update();
        return Result.success();
    }

    private Boolean isAdmin(Long userId) {
        return userId == 1L;
    }

    private Long getUserId() {
        String userId = UserContext.getUserId();
        if (userId == null || userId.isEmpty()) {
            throw new BizException(USER_ERROR, "用户未登录");
        }
        return Long.parseLong(userId);
    }
}
