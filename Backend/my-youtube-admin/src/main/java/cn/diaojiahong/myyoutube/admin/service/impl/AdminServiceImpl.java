package cn.diaojiahong.myyoutube.admin.service.impl;

import cn.diaojiahong.myyoutube.admin.domain.dto.AdminCommentReportPageQueryDTO;
import cn.diaojiahong.myyoutube.admin.domain.dto.AdminVideoPageQueryDTO;
import cn.diaojiahong.myyoutube.admin.domain.vo.AdminCommentReportVO;
import cn.diaojiahong.myyoutube.admin.domain.vo.AdminCommentVO;
import cn.diaojiahong.myyoutube.admin.domain.vo.AdminVideoVO;
import cn.diaojiahong.myyoutube.admin.service.IAdminService;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.search.api.UserDocPort;
import cn.diaojiahong.myyoutube.search.api.VideoDocPort;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.CounterAdjust;
import cn.diaojiahong.myyoutube.video.api.CommentAdminPort;
import cn.diaojiahong.myyoutube.video.api.VideoAdminPort;
import cn.diaojiahong.myyoutube.video.api.dto.VideoSummary;
import cn.diaojiahong.myyoutube.video.api.enums.VideoStatusEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static cn.diaojiahong.myyoutube.common.exception.BizException.USER_ERROR;

/**
 * 管理端服务: 编排 user/video/search 各服务的契约接口
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements IAdminService {

    private static final long ADMIN_USER_ID = 1L;

    private final UserPort userPort;
    private final VideoAdminPort videoAdminPort;
    private final CommentAdminPort commentAdminPort;
    private final VideoDocPort videoDocPort;
    private final UserDocPort userDocPort;
    private final S3StorageUtil storageUtil;

    @Override
    public Result<String> pass(Long videoId) {
        checkAdmin();
        Result<Boolean> markResult = videoAdminPort.markStatus(
                new VideoAdminPort.MarkStatus(videoId, VideoStatusEnum.PUBLISHED.getCode()));
        if (!Boolean.TRUE.equals(markResult.getData())) {
            return Result.fail("发布失败");
        }
        Result<VideoSummary> videoResult = videoAdminPort.getVideo(new VideoAdminPort.VideoId(videoId));
        VideoSummary video = videoResult.getData();
        if (video == null) {
            return Result.fail("视频不存在");
        }
        // 作者作品数 +1
        userPort.adjustCounters(List.of(new CounterAdjust(video.getAuthorId(), "videoCount", 1)));
        // ES 视频文档状态(重建补全词) + 用户文档作品数
        videoDocPort.syncStatus(videoId, new VideoDocPort.StatusSync(
                VideoStatusEnum.PUBLISHED.getCode(), video.getTitle(), video.getDescription(),
                video.getTags(), true));
        userDocPort.adjustVideoCount(video.getAuthorId(), 1);
        return Result.success("发布成功");
    }

    @Override
    public Result<String> ban(Long videoId) {
        checkAdmin();
        Result<VideoSummary> videoResult = videoAdminPort.getVideo(new VideoAdminPort.VideoId(videoId));
        VideoSummary video = videoResult.getData();
        if (video == null) {
            return Result.fail("视频不存在");
        }
        boolean wasPublished = VideoStatusEnum.PUBLISHED.getCode().equals(video.getStatus());
        videoAdminPort.markStatus(new VideoAdminPort.MarkStatus(videoId, VideoStatusEnum.BANNED.getCode()));
        if (wasPublished) {
            userPort.adjustCounters(List.of(new CounterAdjust(video.getAuthorId(), "videoCount", -1)));
            userDocPort.adjustVideoCount(video.getAuthorId(), -1);
        }
        videoDocPort.syncStatus(videoId, new VideoDocPort.StatusSync(
                VideoStatusEnum.BANNED.getCode(), null, null, null, false));
        return Result.success("封禁成功");
    }

    @Override
    public Result<PageResult<AdminVideoVO>> getVideosPage(AdminVideoPageQueryDTO q) {
        checkAdmin();
        Result<PageResult<VideoSummary>> pageResult = videoAdminPort.pageVideos(new VideoAdminPort.AdminVideoQuery(
                q.getVideoStatus() != null ? q.getVideoStatus().getCode() : null,
                q.getVideoCategory() != null ? q.getVideoCategory().getCode() : null,
                q.getVideoType() != null ? q.getVideoType().getCode() : null,
                q.getUserId(),
                q.getPageNo(),
                q.getPageSize()));
        PageResult<VideoSummary> page = pageResult.getData();
        if (page == null || page.getList() == null || page.getList().isEmpty()) {
            return Result.success(PageResult.of(0, 0, new ArrayList<>()));
        }
        List<Long> authorIds = page.getList().stream().map(VideoSummary::getAuthorId).toList();
        Map<Long, String> avatars = userPort.getAvatars(authorIds);
        Map<Long, String> nicknames = userPort.getNicknames(authorIds);
        List<AdminVideoVO> voList = page.getList().stream().map(v -> {
            AdminVideoVO vo = new AdminVideoVO();
            vo.setVideoId(v.getId());
            vo.setTitle(v.getTitle());
            vo.setDescription(v.getDescription());
            vo.setCoverUrl(storageUtil.signGetUrl(v.getCoverUrl(), 60 * 10));
            vo.setVideoUrl(storageUtil.signGetUrl(v.getVideoUrl(), 60 * 10));
            vo.setVideoType(v.getVideoType());
            vo.setAuthorId(v.getAuthorId());
            vo.setAuthorNickname(nicknames.get(v.getAuthorId()));
            vo.setAuthorAvatarUrl(storageUtil.signGetUrl(avatars.get(v.getAuthorId()), 60 * 10));
            return vo;
        }).collect(Collectors.toList());
        return Result.success(PageResult.of(page.getTotal(), page.getPages(), voList));
    }

    @Override
    public Result<Void> blockComment(Long commentId) {
        checkAdmin();
        return commentAdminPort.block(new CommentAdminPort.CommentId(commentId));
    }

    @Override
    public Result<PageResult<AdminCommentVO>> getCommentsPage(PageQuery pageQuery) {
        checkAdmin();
        Result<PageResult<CommentAdminPort.CommentSummary>> page = commentAdminPort.pageBlockedComments(
                new CommentAdminPort.PageQuery(pageQuery.getPageNo(), pageQuery.getPageSize()));
        PageResult<CommentAdminPort.CommentSummary> data = page.getData();
        if (data == null || data.getList() == null || data.getList().isEmpty()) {
            return Result.success(PageResult.of(0, 0, new ArrayList<>()));
        }
        List<AdminCommentVO> voList = data.getList().stream().map(c -> {
            AdminCommentVO vo = new AdminCommentVO();
            vo.setCommentId(c.getCommentId());
            vo.setContent(c.getContent());
            vo.setUserId(c.getUserId());
            vo.setCreateTime(c.getCreateTime());
            return vo;
        }).collect(Collectors.toList());
        return Result.success(PageResult.of(data.getTotal(), data.getPages(), voList));
    }

    @Override
    public Result<Void> restoreComment(Long commentId) {
        checkAdmin();
        return commentAdminPort.restore(new CommentAdminPort.CommentId(commentId));
    }

    @Override
    public Result<PageResult<AdminCommentReportVO>> getReportCommentsPage(AdminCommentReportPageQueryDTO pageQuery) {
        checkAdmin();
        Result<PageResult<CommentAdminPort.ReportSummary>> page = commentAdminPort.pageReports(
                new CommentAdminPort.ReportQuery(
                        pageQuery.getReasonCode() != null ? pageQuery.getReasonCode().getCode() : null,
                        pageQuery.getReporterId(),
                        pageQuery.getPageNo(),
                        pageQuery.getPageSize()));
        PageResult<CommentAdminPort.ReportSummary> data = page.getData();
        if (data == null || data.getList() == null || data.getList().isEmpty()) {
            return Result.success(PageResult.of(0, 0, new ArrayList<>()));
        }
        List<AdminCommentReportVO> voList = data.getList().stream().map(r -> {
            AdminCommentReportVO vo = new AdminCommentReportVO();
            vo.setCommentId(r.getCommentId());
            vo.setRemark(r.getRemark());
            vo.setReporterUserId(r.getReporterUserId());
            vo.setCreateTime(r.getCreateTime());
            vo.setContent(r.getContent());
            return vo;
        }).collect(Collectors.toList());
        return Result.success(PageResult.of(data.getTotal(), data.getPages(), voList));
    }

    @Override
    public Result<Void> ignoreReportComment(Long commentId) {
        checkAdmin();
        return commentAdminPort.ignoreReport(new CommentAdminPort.CommentId(commentId));
    }

    private void checkAdmin() {
        if (!isAdmin(getUserId())) {
            throw new BizException(USER_ERROR, "用户不是管理员");
        }
    }

    private Boolean isAdmin(Long userId) {
        return userId == ADMIN_USER_ID;
    }

    private Long getUserId() {
        String userId = UserContext.getUserId();
        if (userId == null || userId.isEmpty()) {
            throw new BizException(USER_ERROR, "用户未登录");
        }
        return Long.parseLong(userId);
    }
}
