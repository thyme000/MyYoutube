package cn.diaojiahong.myyoutube.transcode.controller;

import cn.diaojiahong.myyoutube.transcode.api.MediaTaskPort;
import cn.diaojiahong.myyoutube.transcode.service.MediaTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * transcode 服务内部接口: 草稿任务结果, 供 video 服务发布链路读取
 */
@RestController
@RequestMapping("/internal/media")
@RequiredArgsConstructor
public class InternalMediaTaskController {

    private final MediaTaskService mediaTaskService;

    @GetMapping("/tasks/{draftId}")
    public List<MediaTaskPort.TaskResult> getTaskResults(@PathVariable Long draftId) {
        return mediaTaskService.listByDraft(draftId).stream()
                .map(t -> new MediaTaskPort.TaskResult(t.getTaskType(), t.getStatus(), t.getResultJson()))
                .toList();
    }
}
