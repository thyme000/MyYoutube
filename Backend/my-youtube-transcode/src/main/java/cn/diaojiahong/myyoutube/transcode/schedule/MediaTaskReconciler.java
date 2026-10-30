package cn.diaojiahong.myyoutube.transcode.schedule;
import cn.diaojiahong.myyoutube.transcode.service.MediaTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MediaTaskReconciler {
    private final MediaTaskService tasks;

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void recover() {
        tasks.recover();
    }

    @Scheduled(fixedDelay = 300000, initialDelay = 300000)
    public void completions() {
        tasks.recoverCompletions();
    }
}
