package cn.diaojiahong.myyoutube.admin.service;

import cn.diaojiahong.myyoutube.admin.domain.dto.AdminVideoPageQueryDTO;
import cn.diaojiahong.myyoutube.admin.domain.vo.AdminVideoVO;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;

public interface IAdminService {
    Result<String> pass(Long videoId);

    Result<String> ban(Long videoId);

    Result<PageResult<AdminVideoVO>> getVideosPage(AdminVideoPageQueryDTO adminVideoPageQueryDTO);

    Result<Void> deleteComment(Long commentId);
}
