package cn.diaojiahong.myyoutube.video.controller;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.video.domain.dto.DynamicPublishDTO;
import cn.diaojiahong.myyoutube.video.domain.vo.DynamicDownloadVO;
import cn.diaojiahong.myyoutube.video.domain.vo.DynamicVO;
import cn.diaojiahong.myyoutube.video.service.IDynamicService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/dynamics")
@Tag(name = "图集动态模块")
@RequiredArgsConstructor
public class DynamicController {

    private final IDynamicService dynamicService;

    @PostMapping("/publish")
    @Operation(summary = "发布图集配乐动态")
    public Result<String> publish(@RequestBody DynamicPublishDTO dto) {
        return dynamicService.publish(dto);
    }

    @GetMapping("/feed")
    @Operation(summary = "动态信息流(按时间倒序分页)")
    public Result<PageResult<DynamicVO>> feed(@ModelAttribute PageQuery pageQuery) {
        return dynamicService.feed(pageQuery);
    }

    @GetMapping("/detail/{id}")
    @Operation(summary = "动态详情")
    public Result<DynamicVO> detail(@PathVariable Long id) {
        return dynamicService.detail(id);
    }

    @PostMapping("/{id}/download")
    @Operation(summary = "请求下载成片(懒触发 ffmpeg 转码, 幂等)")
    public Result<DynamicDownloadVO> requestDownload(@PathVariable Long id) {
        return dynamicService.requestDownload(id);
    }

    @GetMapping("/download/{taskId}/status")
    @Operation(summary = "查询转码任务状态, DONE 时返回成片下载地址")
    public Result<DynamicDownloadVO> getDownloadStatus(@PathVariable Long taskId) {
        return dynamicService.getDownloadStatus(taskId);
    }
}
