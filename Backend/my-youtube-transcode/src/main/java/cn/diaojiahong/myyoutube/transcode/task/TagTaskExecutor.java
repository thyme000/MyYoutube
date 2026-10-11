package cn.diaojiahong.myyoutube.transcode.task;
import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.media.PipelineContracts.TagRequest;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.transcode.domain.enums.MediaTaskType;
import cn.diaojiahong.myyoutube.video.api.ContentPipelinePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Map;
@Component
@RequiredArgsConstructor
public class TagTaskExecutor implements MediaTaskExecutor {
    private final ContentPipelinePort content;
    private final ContentArtifactStore artifacts;
    @Override public MediaTaskType type() { return MediaTaskType.TAG; }
    @Override public String execute(MediaTaskMessage message) {
        var pointer = StructuredJson.object(message.getDependencies().get("SUMMARY"));
        var summary = artifacts.read(pointer.get("artifactKey").getAsString(), ContentArtifacts.Summary.class);
        var result = StructuredJson.object(content.generateTags(new TagRequest(message.getInput().title(), message.getInput().description(), summary.summary())));
        return JsonUtil.toJson(Map.of("artifactKey", artifacts.write(message.getPipelineId(), "TAG", message.getAttempt(), result)));
    }
}
