package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.content.strategy.ContentTypeStrategyRegistry;
import cn.diaojiahong.myyoutube.transcode.api.TranscodePort;
import cn.diaojiahong.myyoutube.transcode.api.dto.*;
import cn.diaojiahong.myyoutube.transcode.api.vo.TranscodeTaskVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ContentDownloadService {
    private final ContentAccessService access;
    private final ContentTypeStrategyRegistry strategies;
    private final TranscodePort transcode;
    private final S3StorageUtil storage;
    public record Download(String contentId, String taskId, String status, String downloadUrl, String errorMessage) {}

    public Download create(Long id, Long viewer) {
        var content = access.readable(id, viewer);
        var media = strategies.get(content.getType()).loadMedia(List.of(id)).get(id);
        if (media == null) throw new BizException(BizException.BIZ_ERROR, "媒体不存在");
        if (media.video() != null) return new Download(id.toString(), null, "DONE", signed(media.video().objectKey()), null);
        var d = media.dynamic();
        var dto = new TranscodeSubmitDTO();
        dto.setContentId(id); dto.setUserId(viewer); dto.setCanvasW(d.canvasW()); dto.setCanvasH(d.canvasH());
        dto.setTransition(d.transition()); dto.setFadeMs(Math.toIntExact(d.fadeMs())); dto.setTotalDurationMs(d.exportDurationMs());
        dto.setAudioObjectKey(d.audio() == null ? null : d.audio().objectKey());
        dto.setAudioDurationMs(d.audio() == null ? 0 : d.audio().durationMs());
        dto.setItems(d.items().stream().map(image -> {
            var item = new TranscodeItemDTO(); item.setImageObjectKey(image.imageObjectKey());
            item.setStartMs(image.startMs()); item.setDurationMs(image.durationMs());
            item.setFocusX(image.focusX()); item.setFocusY(image.focusY()); return item;
        }).toList());
        return view(transcode.submitTask(dto), viewer);
    }
    public Download status(Long taskId, Long viewer) { return view(transcode.getTask(taskId), viewer); }
    private Download view(TranscodeTaskVO task, Long viewer) {
        if (task == null || !Objects.equals(viewer, task.getUserId())) throw new BizException(BizException.BIZ_ERROR, "下载任务不存在");
        access.readable(task.getContentId(), viewer);
        return new Download(task.getContentId().toString(), task.getTaskId().toString(), task.getStatus(),
                "DONE".equals(task.getStatus()) ? signed(task.getResultObjectKey()) : null, task.getErrorMessage());
    }
    private String signed(String key) { return storage.signGetUrl(key, 1200); }
}
