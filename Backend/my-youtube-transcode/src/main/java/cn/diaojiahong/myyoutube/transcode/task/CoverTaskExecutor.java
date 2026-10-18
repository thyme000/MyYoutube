package cn.diaojiahong.myyoutube.transcode.task;

import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.transcode.cover.VideoCoverGenerator;
import cn.diaojiahong.myyoutube.transcode.domain.enums.MediaTaskType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 封面任务复用准备帧，图集直接引用所选原图。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CoverTaskExecutor implements MediaTaskExecutor {

    private final VideoCoverGenerator coverGenerator;

    @Override
    public MediaTaskType type() {
        return MediaTaskType.COVER;
    }

    @Override
    public String execute(MediaTaskMessage msg) {
        var media = msg.getInput().media();
        if (media.preparation() != null) cn.diaojiahong.myyoutube.common.media.PreparedMedia.validate(media, msg.getInput().coverObjectKey());
        long startedAt = System.currentTimeMillis();
        String coverKey;
        String source;
        if (media.dynamic() != null) {
            coverKey = msg.getInput().coverObjectKey();
            source = "DYNAMIC_SELECTED_IMAGE";
        } else if (media.preparation() != null) {
            coverKey = coverGenerator.selectPrepared(media.preparation());
            source = "PREPARED_FRAME";
        } else {
            coverKey = coverGenerator.generate(msg.getPipelineId(), msg.getUserId(), media.video().objectKey());
            source = "GENERATED_FROM_VIDEO";
        }
        log.info("Cover completed pipelineId={} taskId={} attempt={} source={} coverObjectKey={} elapsedMs={}",
                msg.getPipelineId(), msg.getTaskId(), msg.getAttempt(), source, coverKey, System.currentTimeMillis() - startedAt);
        return JsonUtil.toJson(Map.of("coverObjectKey", coverKey));
    }
}
