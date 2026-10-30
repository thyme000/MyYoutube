package cn.diaojiahong.myyoutube.transcode.task;
import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.media.PipelineContracts.TagRequest;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.media.MediaTaskType;
import cn.diaojiahong.myyoutube.video.api.ContentPipelinePort;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class TagTaskExecutor implements MediaTaskExecutor {
    private final ContentPipelinePort content;
    private final ContentArtifactStore artifacts;

    @Override
    public MediaTaskType type() {
        return MediaTaskType.TAG;
    }

    @Override
    public String execute(MediaTaskMessage message) {
        long startedAt = System.currentTimeMillis();
        JsonObject pointer = StructuredJson.object(message.getDependencies().get("SUMMARY"));
        ContentArtifacts.Summary summary = artifacts.read(
                pointer.get("artifactKey").getAsString(),
                ContentArtifacts.Summary.class);
        JsonObject result = StructuredJson.object(content.generateTags(new TagRequest(
                message.getInput().title(),
                message.getInput().description(),
                summary.summary())));
        int tags = result.has("tags") && result.get("tags").isJsonArray() ? result.getAsJsonArray("tags").size() : 0;
        log.info("Tag completed pipelineId={} taskId={} attempt={} category={} tags={} elapsedMs={}",
                message.getPipelineId(), message.getTaskId(), message.getAttempt(),
                result.has("category") ? result.get("category").getAsString() : null, tags,
                System.currentTimeMillis() - startedAt);
        return JsonUtil.toJson(Map.of(
                "artifactKey",
                artifacts.write(message.getPipelineId(), "TAG", message.getAttempt(), result)));
    }
}
