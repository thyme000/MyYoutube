package cn.diaojiahong.myyoutube.admin.service.impl;

import cn.diaojiahong.myyoutube.admin.domain.dto.AdminCommentReportPageQueryDTO;
import cn.diaojiahong.myyoutube.admin.domain.dto.AdminContentPageQueryDTO;
import cn.diaojiahong.myyoutube.admin.domain.vo.AdminCommentReportVO;
import cn.diaojiahong.myyoutube.admin.domain.vo.AdminCommentVO;
import cn.diaojiahong.myyoutube.admin.domain.vo.AdminContentVO;
import cn.diaojiahong.myyoutube.admin.service.IAdminService;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.result.ResultCode;
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
    private final ContentAdminPort contentAdminPort;
    private final CommentAdminPort commentAdminPort;
    private final S3StorageUtil storageUtil;

    @Override
    public String pass(Long contentId, String reason) {
        checkAdmin();
        var result = contentAdminPort.markStatus(new ContentAdminPort.MarkStatus(contentId, ContentStatusEnum.PUBLISHED.getCode(), reason, getUserId()));
        requireOk(result);
        if (!Boolean.TRUE.equals(result.getData())) throw new BizException(BizException.BIZ_ERROR, "审核状态已变化");
        return "审核通过";
    }

    @Override
    public String ban(Long contentId, String reason) {
        checkAdmin();
        var result = contentAdminPort.markStatus(new ContentAdminPort.MarkStatus(contentId, ContentStatusEnum.BANNED.getCode(), reason, getUserId()));
        requireOk(result);
        if (!Boolean.TRUE.equals(result.getData())) throw new BizException(BizException.BIZ_ERROR, "审核状态已变化");
        return "审核拒绝或封禁成功";
    }

    @Override
    public PageResult<AdminContentVO> getContentsPage(AdminContentPageQueryDTO q) {
        checkAdmin();
        Result<PageResult<ContentSummary>> pageResult = contentAdminPort.pageContents(new ContentAdminPort.AdminContentQuery(
                q.getStatusCode(),
                q.getCategoryCode(),
                q.getUserId(),
                q.getPageNo(),
                q.getPageSize(), q.getType()));
        PageResult<ContentSummary> page = pageResult.getData();
        if (page == null || page.getList() == null || page.getList().isEmpty()) {
            return PageResult.of(0, 0, new ArrayList<>());
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
        requireOk(commentAdminPort.block(new CommentAdminPort.CommentId(commentId)));
    }

    @Override
    public PageResult<AdminCommentVO> getCommentsPage(PageQuery pageQuery) {
        checkAdmin();
        Result<PageResult<CommentAdminPort.CommentSummary>> page = commentAdminPort.pageBlockedComments(
                new CommentAdminPort.PageQuery(pageQuery.getPageNo(), pageQuery.getPageSize()));
        PageResult<CommentAdminPort.CommentSummary> data = page.getData();
        if (data == null || data.getList() == null || data.getList().isEmpty()) {
            return PageResult.of(0, 0, new ArrayList<>());
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
        requireOk(commentAdminPort.restore(new CommentAdminPort.CommentId(commentId)));
    }

    @Override
    public PageResult<AdminCommentReportVO> getReportCommentsPage(AdminCommentReportPageQueryDTO pageQuery) {
        checkAdmin();
        Result<PageResult<CommentAdminPort.ReportSummary>> page = commentAdminPort.pageReports(
                new CommentAdminPort.ReportQuery(
                        pageQuery.getReasonCode() != null ? pageQuery.getReasonCode().getCode() : null,
                        pageQuery.getReporterId(),
                        pageQuery.getPageNo(),
                        pageQuery.getPageSize()));
        PageResult<CommentAdminPort.ReportSummary> data = page.getData();
        if (data == null || data.getList() == null || data.getList().isEmpty()) {
            return PageResult.of(0, 0, new ArrayList<>());
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
        requireOk(commentAdminPort.ignoreReport(new CommentAdminPort.CommentId(commentId)));
    }

    /** 内部端口返回的 Result 拆包: 非 200 转业务异常, Result 不穿透本层 */
    private void requireOk(Result<?> r) {
        if (r == null || r.getCode() != ResultCode.SUCCESS.getCode()) {
            throw new BizException(BizException.BIZ_ERROR, r != null ? r.getMessage() : "下游服务无响应");
        }
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
