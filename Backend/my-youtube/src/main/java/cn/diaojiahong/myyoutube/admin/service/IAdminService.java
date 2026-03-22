package cn.diaojiahong.myyoutube.admin.service;

import cn.diaojiahong.myyoutube.common.result.Result;

public interface IAdminService {
    Result<String> pass(Long videoId);

    Result<String> ban(Long videoId);
}
