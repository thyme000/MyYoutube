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
    String pass(Long videoId);

    String ban(Long videoId);

    PageResult<AdminVideoVO> getVideosPage(AdminVideoPageQueryDTO adminVideoPageQueryDTO);

    void blockComment(Long commentId);

    PageResult<AdminCommentVO> getCommentsPage(PageQuery pageQuery);

    void restoreComment(Long commentId);

    PageResult<AdminCommentReportVO> getReportCommentsPage(AdminCommentReportPageQueryDTO pageQuery);

    void ignoreReportComment(Long commentId);
}
