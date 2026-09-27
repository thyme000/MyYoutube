package cn.diaojiahong.myyoutube.admin.service;

import cn.diaojiahong.myyoutube.admin.domain.dto.AdminCommentReportPageQueryDTO;
import cn.diaojiahong.myyoutube.admin.domain.dto.AdminVideoPageQueryDTO;
import cn.diaojiahong.myyoutube.admin.domain.vo.AdminCommentReportVO;
import cn.diaojiahong.myyoutube.admin.domain.vo.AdminCommentVO;
import cn.diaojiahong.myyoutube.admin.domain.vo.AdminVideoVO;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;

public interface IAdminService {
    Result<String> pass(Long videoId);

    Result<String> ban(Long videoId);

    Result<PageResult<AdminVideoVO>> getVideosPage(AdminVideoPageQueryDTO adminVideoPageQueryDTO);

    Result<Void> blockComment(Long commentId);

    Result<PageResult<AdminCommentVO>> getCommentsPage(PageQuery pageQuery);

    Result<Void> restoreComment(Long commentId);

    Result<PageResult<AdminCommentReportVO>> getReportCommentsPage(AdminCommentReportPageQueryDTO pageQuery);

    Result<Void> ignoreReportComment(Long commentId);
}
