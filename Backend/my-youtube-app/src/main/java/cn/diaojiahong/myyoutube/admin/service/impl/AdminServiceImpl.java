package cn.diaojiahong.myyoutube.admin.service.impl;

import cn.diaojiahong.myyoutube.admin.domain.dto.AdminCommentReportPageQueryDTO;
import cn.diaojiahong.myyoutube.admin.domain.dto.AdminVideoPageQueryDTO;
import cn.diaojiahong.myyoutube.admin.domain.vo.AdminCommentReportVO;
import cn.diaojiahong.myyoutube.admin.domain.vo.AdminCommentVO;
import cn.diaojiahong.myyoutube.admin.domain.vo.AdminVideoVO;
import cn.diaojiahong.myyoutube.admin.service.IAdminService;
import cn.diaojiahong.myyoutube.comment.domain.enums.CommentReportStatusEnum;
import cn.diaojiahong.myyoutube.comment.domain.enums.CommentStatusEnum;
import cn.diaojiahong.myyoutube.comment.domain.po.CommentReport;
import cn.diaojiahong.myyoutube.comment.domain.po.VideoComment;
import cn.diaojiahong.myyoutube.comment.service.ICommentReportService;
import cn.diaojiahong.myyoutube.comment.service.IVideoCommentService;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.recommend.domain.doc.VideoDocRepository;
import cn.diaojiahong.myyoutube.search.domain.doc.UserDocRepository;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import cn.diaojiahong.myyoutube.video.api.enums.VideoStatusEnum;
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
import java.util.function.Function;
import java.util.stream.Collectors;

import static cn.diaojiahong.myyoutube.common.exception.BizException.USER_ERROR;
import static cn.diaojiahong.myyoutube.video.api.enums.VideoStatusEnum.BANNED;
import static cn.diaojiahong.myyoutube.video.api.enums.VideoStatusEnum.PUBLISHED;

@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements IAdminService {

    private final IVideoService videoService;
    private final IUserService userService;
    private final VideoDocRepository videoDocRepository;
    private final UserDocRepository userDocRepository;
    private final IVideoCommentService videoCommentService;
    private final S3StorageUtil storageUtil;
    private final ICommentReportService commentReportService;

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
                .orderByDesc(Video::getCreateTime)
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
            vo.setCoverUrl(storageUtil.signGetUrl(video.getCoverUrl(), 60 * 10));
            vo.setVideoUrl(storageUtil.signGetUrl(video.getVideoUrl(), 60 * 10));
            vo.setVideoType(video.getVideoType());
            vo.setVideoCategory(video.getCategory());
            vo.setCreateTime(video.getCreateTime());
            vo.setAuthorId(video.getAuthorId());
            vo.setAuthorNickname(authorNicknames.get(video.getAuthorId()));
            vo.setAuthorAvatarUrl(storageUtil.signGetUrl(authorAvatarUrls.get(video.getAuthorId()), 60 * 10));
            voList.add(vo);
        });
        return Result.success(PageResult.of(page.getTotal(), page.getPages(), voList));
    }

    @Transactional
    @Override
    public Result<Void> blockComment(Long commentId) {
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
        // 将评论设为违规
        comment.setStatus(CommentStatusEnum.BLOCKED.getCode());
        videoCommentService.updateById(comment);
        // 将用户举报设为已处理
        commentReportService.lambdaUpdate()
                .eq(CommentReport::getCommentId, commentId)
                .set(CommentReport::getStatus, CommentReportStatusEnum.PROCESSED)
                .update();
        return Result.success();
    }

    @Override
    public Result<PageResult<AdminCommentVO>> getCommentsPage(PageQuery pageQuery) {
        Long userId = getUserId();
        // 判断是否时管理员
        if (!isAdmin(userId)) {
            throw new BizException(USER_ERROR, "用户不是管理员");
        }
        Page<VideoComment> page = videoCommentService.lambdaQuery()
                .eq(VideoComment::getStatus, CommentStatusEnum.BLOCKED.getCode())
                .orderByDesc(VideoComment::getCreateTime)
                .page(pageQuery.toPage());
        if (page == null || page.getRecords().isEmpty()) {
            return Result.success(PageResult.of(0, 0, new ArrayList<>()));
        }
        // 转vo
        List<AdminCommentVO> voList = new ArrayList<>();
        page.getRecords().forEach(comment -> {
            AdminCommentVO vo = new AdminCommentVO();
            vo.setCommentId(comment.getId());
            vo.setContent(comment.getContent());
            vo.setUserId(comment.getUserId());
            vo.setCreateTime(comment.getCreateTime());
            voList.add(vo);
        });
        return Result.success(PageResult.of(page.getTotal(), page.getPages(), voList));
    }

    @Override
    public Result<Void> restoreComment(Long commentId) {
        Long userId = getUserId();
        // 判断是否时管理员
        if (!isAdmin(userId)) {
            throw new BizException(USER_ERROR, "用户不是管理员");
        }
        VideoComment comment = videoCommentService.getById(commentId);
        if (comment == null) {
            return Result.fail("评论不存在");
        }
        if (comment.getStatus().equals(CommentStatusEnum.DELETED.getCode())) {
            return Result.fail("评论已被删除");
        }
        if (comment.getStatus().equals(CommentStatusEnum.VISIBLE.getCode())) {
            return Result.success();
        }
        // 将评论设为正常
        comment.setStatus(CommentStatusEnum.VISIBLE.getCode());
        videoCommentService.updateById(comment);
        return Result.success();
    }

    @Override
    public Result<PageResult<AdminCommentReportVO>> getReportCommentsPage(AdminCommentReportPageQueryDTO pageQuery) {
        Long userId = getUserId();
        // 判断是否时管理员
        if (!isAdmin(userId)) {
            throw new BizException(USER_ERROR, "用户不是管理员");
        }
        Page<CommentReport> page = commentReportService.lambdaQuery()
                .eq(CommentReport::getStatus, CommentReportStatusEnum.PENDING.getCode())
                .eq(pageQuery.getReasonCode() != null, CommentReport::getReasonCode, pageQuery.getReasonCode())
                .eq(pageQuery.getReporterId() != null, CommentReport::getReporterUserId, pageQuery.getReporterId())
                .orderByDesc(CommentReport::getCreatedAt)
                .page(pageQuery.toPage());
        if (page == null || page.getRecords() == null) {
            return Result.success(PageResult.of(0, 0, new ArrayList<>()));
        }
        List<CommentReport> records = page.getRecords();
        List<Long> list = records.stream().map(CommentReport::getCommentId).toList();
        if (list.isEmpty()) {
            return Result.success(PageResult.of(0, 0, new ArrayList<>()));
        }
        List<VideoComment> videoComments = videoCommentService.lambdaQuery()
                .eq(VideoComment::getStatus, CommentStatusEnum.VISIBLE.getCode())
                .in(VideoComment::getId, list)
                .list();
        if (videoComments.isEmpty()) {
            return Result.success(PageResult.of(0, 0, new ArrayList<>()));
        }
        // 转map
        Map<Long, VideoComment> videoCommentMap = videoComments.stream()
                .collect(Collectors.toMap(VideoComment::getId, Function.identity()));
        // 转vo
        List<AdminCommentReportVO> voList = new ArrayList<>();
        records.forEach(report -> {
            AdminCommentReportVO vo = new AdminCommentReportVO();
            vo.setCommentId(report.getCommentId());
            vo.setRemark(report.getRemark());
            vo.setReporterUserId(report.getReporterUserId());
            vo.setCreateTime(report.getCreatedAt());
            vo.setContent(videoCommentMap.get(report.getCommentId()).getContent());
            voList.add(vo);
        });
        return Result.success(PageResult.of(page.getTotal(), page.getPages(), voList));
    }

    @Override
    public Result<Void> ignoreReportComment(Long commentId) {
        Long userId = getUserId();
        // 判断是否时管理员
        if (!isAdmin(userId)) {
            throw new BizException(USER_ERROR, "用户不是管理员");
        }
        // 将举报设为已处理
        commentReportService.lambdaUpdate()
                        .eq(CommentReport::getCommentId, commentId)
                        .set(CommentReport::getStatus, CommentReportStatusEnum.PROCESSED.getCode())
                        .update();
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
