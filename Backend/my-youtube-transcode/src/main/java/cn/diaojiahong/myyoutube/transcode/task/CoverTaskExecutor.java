package cn.diaojiahong.myyoutube.transcode.task;

import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.transcode.cover.VideoCoverGenerator;
import cn.diaojiahong.myyoutube.transcode.domain.enums.MediaTaskType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 封面任务复用准备帧，图集直接引用所选原图。
 */
@Component
@RequiredArgsConstructor
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
        String coverKey = media.dynamic() != null ? msg.getInput().coverObjectKey()
                : media.preparation() != null ? coverGenerator.selectPrepared(media.preparation())
                : coverGenerator.generate(msg.getPipelineId(), msg.getUserId(), media.video().objectKey());
        return JsonUtil.toJson(Map.of("coverObjectKey", coverKey));
    }
}
