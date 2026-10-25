package cn.diaojiahong.myyoutube.video.controller;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.video.domain.dto.AudioPublishDTO;
import cn.diaojiahong.myyoutube.video.domain.vo.AudioVO;
import cn.diaojiahong.myyoutube.video.service.IAudioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/audios")
@Tag(name = "音频库模块")
@RequiredArgsConstructor
public class AudioController {

    private final IAudioService audioService;

    @PostMapping("/publish")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    @Operation(summary = "受理音频，后台校验文件后等待审核")
    public Result<String> publish(@RequestBody AudioPublishDTO dto) {
        return Result.success(audioService.publish(dto));
    }

    @GetMapping("/mine/{id}")
    @Operation(summary = "查询本人的音频处理状态")
    public Result<AudioVO> getMine(@PathVariable Long id) { return Result.success(audioService.getMine(id)); }

    @PostMapping("/{id}/retry")
    @Operation(summary = "重试本人处理失败的音频")
    public Result<Boolean> retry(@PathVariable Long id) { return Result.success(audioService.retry(id)); }

    @GetMapping("/my")
    @Operation(summary = "我的音频列表(含私密)")
    public Result<PageResult<AudioVO>> myAudios(@ModelAttribute PageQuery pageQuery) {
        return Result.success(audioService.myAudios(pageQuery));
    }

    @GetMapping("/library")
    @Operation(summary = "公开音频库(可选关键词搜索)")
    public Result<PageResult<AudioVO>> library(@RequestParam(required = false) String keyword,
                                               @ModelAttribute PageQuery pageQuery) {
        return Result.success(audioService.library(keyword, pageQuery));
    }

    @PostMapping("/{id}/visibility")
    @Operation(summary = "切换可见性(仅上传者)")
    public Result<Void> setVisibility(@PathVariable Long id, @RequestParam String visibility) {
        audioService.setVisibility(id, visibility);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除音频(仅上传者, 已引用动态持快照不受影响)")
    public Result<Void> delete(@PathVariable Long id) {
        audioService.delete(id);
        return Result.success();
    }
}
