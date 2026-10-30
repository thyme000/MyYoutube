package cn.diaojiahong.myyoutube.admin.service.impl;

import cn.diaojiahong.myyoutube.admin.security.AdminAuthorization;
import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.admin.domain.dto.AdminCommentReportPageQueryDTO;
import cn.diaojiahong.myyoutube.admin.domain.dto.AdminContentPageQueryDTO;
import cn.diaojiahong.myyoutube.admin.domain.vo.AdminCommentReportVO;
import cn.diaojiahong.myyoutube.admin.domain.vo.AdminCommentVO;
import cn.diaojiahong.myyoutube.admin.domain.vo.AdminContentVO;
import cn.diaojiahong.myyoutube.admin.service.IAdminService;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummaries;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.video.api.CommentAdminPort;
import cn.diaojiahong.myyoutube.video.api.ContentAdminPort;
import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;
import cn.diaojiahong.myyoutube.video.api.enums.ContentStatusEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 管理端服务: 编排 user/video/search 各服务的契约接口
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements IAdminService {

    private final UserPort userPort;
    private final ContentAdminPort contentAdminPort;
    private final CommentAdminPort commentAdminPort;
    private final S3StorageUtil storageUtil;
    private final AdminAuthorization authorization;

    @Override
    public String pass(Long contentId, String reason) {
        checkAdmin();
        Boolean result = contentAdminPort.markStatus(new ContentAdminPort.MarkStatus(
                contentId,
                ContentStatusEnum.PUBLISHED.getCode(),
                reason,
                getUserId()));
        requireChanged(result);
        return "审核通过";
    }

    @Override
    public String ban(Long contentId, String reason) {
        checkAdmin();
        Boolean result = contentAdminPort.markStatus(new ContentAdminPort.MarkStatus(
                contentId,
                ContentStatusEnum.BANNED.getCode(),
                reason,
                getUserId()));
        requireChanged(result);
        return "审核拒绝或封禁成功";
    }

    @Override
    public PageResult<AdminContentVO> getContentsPage(AdminContentPageQueryDTO q) {
        checkAdmin();
        PageResult<ContentSummary> page = requirePage(contentAdminPort.pageContents(new ContentAdminPort.AdminContentQuery(
                q.getStatusCode(),
                q.getCategoryCode(),
                q.getUserId(),
                q.getPageNo(),
                q.getPageSize(), q.getType())));
        if (page.getList().isEmpty()) {
            return PageResult.of(page.getTotal(), page.getPages(), List.of());
        }

        List<Long> authorIds = page.getList().stream().map(ContentSummary::getAuthorId).toList();
        List<UserSummary> authors = userPort.listByIds(authorIds);
        Map<Long, String> avatars = UserSummaries.toAvatarMap(authors);
        Map<Long, String> nicknames = UserSummaries.toNicknameMap(authors);
        List<AdminContentVO> voList = page.getList().stream().map(v -> {
            AdminContentVO vo = cn.hutool.core.bean.BeanUtil.copyProperties(v, AdminContentVO.class);
            vo.setTitle(v.getTitle());
            vo.setDescription(v.getDescription());
            vo.setCoverUrl(storageUtil.signGetUrl(v.getCoverUrl(), 60 * 10));
            vo.setVideoUrl(storageUtil.signGetUrl(v.getVideoUrl(), 60 * 10));
            vo.setAuthorId(v.getAuthorId());
            vo.setAuthorNickname(nicknames.get(v.getAuthorId()));
            vo.setAuthorAvatarUrl(storageUtil.signGetUrl(avatars.get(v.getAuthorId()), 60 * 10));
            return vo;
        }).collect(Collectors.toList());
        return PageResult.of(page.getTotal(), page.getPages(), voList);
    }

    @Override
    public void blockComment(Long commentId) {
        checkAdmin();
        commentAdminPort.block(new CommentAdminPort.CommentId(commentId));
    }

    @Override
    public PageResult<AdminCommentVO> getCommentsPage(PageQuery pageQuery) {
        checkAdmin();
        PageResult<CommentAdminPort.CommentSummary> data = requirePage(commentAdminPort.pageBlockedComments(
                new CommentAdminPort.PageQuery(pageQuery.getPageNo(), pageQuery.getPageSize())));
        if (data.getList().isEmpty()) {
            return PageResult.of(data.getTotal(), data.getPages(), List.of());
        }

        List<AdminCommentVO> voList = data.getList().stream().map(c -> {
            AdminCommentVO vo = new AdminCommentVO();
            vo.setCommentId(c.getCommentId());
            vo.setContent(c.getContent());
            vo.setUserId(c.getUserId());
            vo.setCreateTime(c.getCreateTime());
            return vo;
        }).collect(Collectors.toList());
        return PageResult.of(data.getTotal(), data.getPages(), voList);
    }

    @Override
    public void restoreComment(Long commentId) {
        checkAdmin();
        commentAdminPort.restore(new CommentAdminPort.CommentId(commentId));
    }

    @Override
    public PageResult<AdminCommentReportVO> getReportCommentsPage(AdminCommentReportPageQueryDTO pageQuery) {
        checkAdmin();
        PageResult<CommentAdminPort.ReportSummary> data = requirePage(commentAdminPort.pageReports(
                new CommentAdminPort.ReportQuery(
                        pageQuery.getReasonCode() != null ? pageQuery.getReasonCode().getCode() : null,
                        pageQuery.getReporterId(),
                        pageQuery.getPageNo(),
                        pageQuery.getPageSize())));
        if (data.getList().isEmpty()) {
            return PageResult.of(data.getTotal(), data.getPages(), List.of());
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
        return PageResult.of(data.getTotal(), data.getPages(), voList);
    }

    @Override
    public void ignoreReportComment(Long commentId) {
        checkAdmin();
        commentAdminPort.ignoreReport(new CommentAdminPort.CommentId(commentId));
    }

    private void requireChanged(Boolean changed) {
        if (changed == null) throw new DependencyException(ErrorCode.DEPENDENCY_FAILURE, "下游未返回审核结果", null);
        if (!changed) throw new BizException(ErrorCode.STATE_CONFLICT, "审核状态已变化");
    }

    private <T> PageResult<T> requirePage(PageResult<T> page) {
        if (page == null || page.getList() == null)
            throw new DependencyException(ErrorCode.DEPENDENCY_FAILURE, "下游未返回分页结果", null);
        return page;
    }

    private void checkAdmin() {
        authorization.requireAdmin();
    }

    private Long getUserId() {
        return UserContext.requireUserId();
    }
}
