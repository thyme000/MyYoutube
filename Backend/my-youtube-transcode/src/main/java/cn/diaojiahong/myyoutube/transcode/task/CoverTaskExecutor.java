package cn.diaojiahong.myyoutube.transcode.task;

import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.transcode.cover.VideoCoverGenerator;
import cn.diaojiahong.myyoutube.transcode.domain.enums.MediaTaskType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 封面任务: 抽帧评分 + LLM 选帧, 生成逻辑与旧发布链路共用 {@link VideoCoverGenerator}
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
        String coverKey = coverGenerator.generate(msg.getDraftId(), msg.getUserId(), msg.getObjectKey());
        return JsonUtil.toJson(Map.of("coverObjectKey", coverKey));
    }
}
