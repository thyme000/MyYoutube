package cn.diaojiahong.myyoutube.transcode.chain.dynamic;

import cn.diaojiahong.myyoutube.transcode.domain.po.TranscodeTask;
import cn.diaojiahong.myyoutube.transcode.service.TranscodeTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class CompleteStep extends TranscodeStep {

    private final TranscodeTaskService taskService;
    private final TranscodeNotifier notifier;

    public CompleteStep(TranscodeTaskService taskService, TranscodeNotifier notifier) {
        super(50);
        this.taskService = taskService;
        this.notifier = notifier;
    }

    @Override
    protected void doApply(TranscodeContext ctx) {
        List<TranscodeTask> affected = taskService.listInflightByKey(ctx.getTask().getTaskKey());
        taskService.completeByKey(ctx.getTask().getTaskKey(), ctx.getObjectKey());
        notifier.notifyAffected(affected, "TRANSCODE_DONE");
        log.info("动态成片生成完成 taskId={} taskKey={} objectKey={} size={} affected={} elapsedMs={}",
                ctx.getTask().getId(), ctx.getTask().getTaskKey(), ctx.getObjectKey(), ctx.getMp4Size(),
                affected.size(), System.currentTimeMillis() - ctx.getStartedAtMillis());
    }
}
