package cn.diaojiahong.myyoutube.video.controller;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.video.domain.dto.AudioUploadDTO;
import cn.diaojiahong.myyoutube.video.domain.vo.AudioUploadVO;
import cn.diaojiahong.myyoutube.video.service.AudioUploadService;
import cn.diaojiahong.myyoutube.video.domain.vo.AudioVO;
import cn.diaojiahong.myyoutube.video.domain.vo.AudioPage;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/audios")
@Tag(name = "音频库模块")
@RequiredArgsConstructor
public class AudioController {

    private final IAudioService audioService;
    private final AudioUploadService uploads;

    @PostMapping("/uploads")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "登记音频上传并返回绑定文件信息的直传地址")
    public Result<AudioUploadVO> upload(@RequestBody AudioUploadDTO dto) {
        return Result.success(uploads.start(dto));
    }

    @PostMapping("/uploads/{id}/complete")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "提醒后端检查本人已登记的音频上传")
    public Result<Void> complete(@PathVariable Long id) {
        uploads.complete(id);
        return Result.success();
    }

    @GetMapping("/mine/{id}")
    @Operation(summary = "查询本人的音频处理状态")
    public Result<AudioVO> getMine(@PathVariable Long id) {
        return Result.success(audioService.getMine(id));
    }

    @PostMapping("/{id}/retry")
    @Operation(summary = "重试本人处理失败的音频")
    public Result<Boolean> retry(@PathVariable Long id) {
        return Result.success(audioService.retry(id));
    }

    @GetMapping("/my")
    @Operation(summary = "我的音频列表(含私密)")
    public Result<PageResult<AudioVO>> myAudios(@ModelAttribute PageQuery pageQuery) {
        return Result.success(audioService.myAudios(pageQuery));
    }

    @GetMapping("/library")
    @Operation(summary = "公开音频库(可选关键词搜索)")
    public Result<AudioPage> library(@RequestParam(required = false) String keyword,
                                     @ModelAttribute PageQuery pageQuery) {
        return Result.success(audioService.library(keyword, pageQuery));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询公开音频详情")
    public Result<AudioVO> detail(@PathVariable Long id) {
        return Result.success(audioService.detail(id));
    }

    @GetMapping("/mine/search")
    @Operation(summary = "按名称搜索本人的音频")
    public Result<AudioPage> searchMine(@RequestParam(required = false) String keyword, @ModelAttribute PageQuery page) {
        return Result.success(audioService.searchMine(keyword, page));
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
