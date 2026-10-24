package cn.diaojiahong.myyoutube.transcode.controller;
import cn.diaojiahong.myyoutube.transcode.api.MediaTaskPort;
import cn.diaojiahong.myyoutube.transcode.service.MediaTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/internal/media")
@RequiredArgsConstructor
public class InternalMediaTaskController implements MediaTaskPort {
    private final MediaTaskService tasks;
    private final cn.diaojiahong.myyoutube.transcode.preparation.PreparationRegistry preparations;
    @Override public void prepare(cn.diaojiahong.myyoutube.common.media.PreparationContracts.Request request) { preparations.start(request); }
    @Override public cn.diaojiahong.myyoutube.common.media.PreparationContracts.View preparation(Long id) { return preparations.view(id); }
    @Override public boolean retryPreparation(Long id) { return tasks.retryPreparation(id); }
    @Override public List<TaskResult> getTaskResults(Long ownerId) { return tasks.listByDraft(ownerId).stream().map(t -> new TaskResult(t.getTaskType(), t.getStatus().getCode(), t.getResultJson())).toList(); }
    @Override public PipelineView pipeline(Long pipelineId) { return tasks.view(pipelineId); }
    @Override public List<PipelineView> waiting(int page, int size) { return tasks.waiting(page, size); }
    @Override public boolean retry(Long pipelineId) { return tasks.retry(pipelineId); }
    @Override public void received(Long pipelineId) { tasks.received(pipelineId); }
    @Override public void resultReceived(Long taskId, int attempt) { tasks.resultReceived(taskId, attempt); }
}
