package cn.diaojiahong.myyoutube.admin.service.impl;

import cn.diaojiahong.myyoutube.admin.service.IAdminService;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.service.IVideoService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import static cn.diaojiahong.myyoutube.video.domain.enums.VideoStatusEnum.BANNED;
import static cn.diaojiahong.myyoutube.video.domain.enums.VideoStatusEnum.PUBLISHED;

@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements IAdminService {

    private final IVideoService videoService;

    @Override
    public Result<String> pass(Long videoId) {
        boolean success = videoService.lambdaUpdate()
                .set(Video::getStatus, PUBLISHED.getCode())
                .eq(Video::getId, videoId)
                .update();
        if (!success) {
            return Result.fail("发布失败");
        }
        return Result.success("发布成功");
    }

    @Override
    public Result<String> ban(Long videoId) {
        boolean success = videoService.lambdaUpdate()
                .set(Video::getStatus, BANNED.getCode())
                .eq(Video::getId, videoId)
                .update();
        if (!success) {
            return Result.fail("禁用失败");
        }
        return Result.success("禁用成功");
    }
}
