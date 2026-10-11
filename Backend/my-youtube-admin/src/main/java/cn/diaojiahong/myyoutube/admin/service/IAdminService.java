package cn.diaojiahong.myyoutube.admin.service;

import cn.diaojiahong.myyoutube.admin.domain.dto.AdminCommentReportPageQueryDTO;
import cn.diaojiahong.myyoutube.admin.domain.dto.AdminContentPageQueryDTO;
import cn.diaojiahong.myyoutube.admin.domain.vo.AdminCommentReportVO;
import cn.diaojiahong.myyoutube.admin.domain.vo.AdminCommentVO;
import cn.diaojiahong.myyoutube.admin.domain.vo.AdminContentVO;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;

public interface IAdminService {
    String pass(Long contentId, String reason);

    String ban(Long contentId, String reason);

    PageResult<AdminContentVO> getContentsPage(AdminContentPageQueryDTO adminVideoPageQueryDTO);

    void blockComment(Long commentId);

    PageResult<AdminCommentVO> getCommentsPage(PageQuery pageQuery);

    void restoreComment(Long commentId);

    PageResult<AdminCommentReportVO> getReportCommentsPage(AdminCommentReportPageQueryDTO pageQuery);

    void ignoreReportComment(Long commentId);
}
