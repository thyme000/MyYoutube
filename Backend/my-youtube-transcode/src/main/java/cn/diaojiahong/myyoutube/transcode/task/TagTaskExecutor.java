package cn.diaojiahong.myyoutube.transcode.task;

import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import cn.diaojiahong.myyoutube.transcode.config.MediaProperties;
import cn.diaojiahong.myyoutube.transcode.domain.enums.MediaTaskType;
import cn.diaojiahong.myyoutube.transcode.ffmpeg.FFmpegUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 视觉打标任务: 抽样帧送 LLM 产出 原始标签名+类目+置信度。
 * 只做计算不做映射: 词库 slug 映射/新词护栏/落库由发布时 video 服务统一执行
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TagTaskExecutor implements MediaTaskExecutor {

    private static final int FRAME_COUNT = 4;
    private static final String PROMPT = """
            你是内容平台的视频打标器。下面给出同一段视频的 %d 张抽样帧, 判断内容所属类目并产出标签。
            规则:
            1. 类目只能从 GAME MOVIE MUSIC KNOWLEDGE LIFE TECH ENTERTAINMENT OTHER 中选一个;
            2. 标签是描述内容主题的规范名词, 每个 2~6 个字, 总数 3~5 个;
            3. 每个标签给出 0~1 的置信度;
            4. 只输出 JSON, 不要任何解释, 格式: {"category":"CODE","tags":[{"name":"标签名","confidence":0.9}]}""";

    private final LlmPort llmPort;
    private final MediaProperties mediaProperties;
    private final S3StorageUtil storageUtil;

    @Override
    public MediaTaskType type() {
        return MediaTaskType.TAG;
    }

    @Override
    public String execute(MediaTaskMessage msg) throws Exception {
        // 预签名 2h, 足够抽帧按 Range 拉取
        String url = storageUtil.signGetUrl(msg.getObjectKey(), 3600 * 2);

        String taskId = UUID.randomUUID().toString().replace("-", "");
        Path work = Path.of(System.getProperty("java.io.tmpdir"), "my-youtube", "media-tag",
                LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE), taskId);
        Files.createDirectories(work);
        try {
            FFmpegUtil.Options opt = new FFmpegUtil.Options();
            opt.sampleCount = FRAME_COUNT;
            opt.commandTimeout = Duration.ofSeconds(60);
            List<FFmpegUtil.FrameScore> candidates = FFmpegUtil.extractAndScoreCandidates(url, work, opt);
            if (candidates.isEmpty()) {
                throw new IllegalStateException("打标抽帧为空");
            }
            List<FFmpegUtil.FrameScore> top = candidates.stream()
                    .sorted(Comparator.comparingDouble(FFmpegUtil.FrameScore::totalScore).reversed())
                    .limit(3)
                    .toList();
            return callLlm(top);
        } finally {
            try (var s = Files.walk(work)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignore) {}
                });
            } catch (Exception ignore) {}
        }
    }

    private String callLlm(List<FFmpegUtil.FrameScore> frames) throws Exception {
        List<LlmPort.ContentPart> parts = new ArrayList<>();
        parts.add(new LlmPort.ContentPart("text", PROMPT.formatted(frames.size()), null));
        for (FFmpegUtil.FrameScore frame : frames) {
            parts.add(new LlmPort.ContentPart("image_url", null,
                    new LlmPort.ContentPart.ImageUrl(toDataUrl(frame.framePath()))));
        }
        LlmPort.ChatRequest request = new LlmPort.ChatRequest(
                List.of(new LlmPort.Message("user", null, parts)),
                mediaProperties.getTagVisionModel().isEmpty() ? null : mediaProperties.getTagVisionModel(),
                0.2, 512);
        String content = llmPort.chat(request).getContent();
        return normalize(content);
    }

    /** 校验并紧凑化模型输出: 只保留 category + tags, 防线(置信度/词库)留给发布侧 */
    private String normalize(String content) {
        String json = extractJson(content);
        JsonObject obj = JsonUtil.GSON.fromJson(json, JsonObject.class);
        String category = obj.has("category") && !obj.get("category").isJsonNull()
                ? obj.get("category").getAsString() : "OTHER";
        JsonArray out = new JsonArray();
        if (obj.has("tags") && obj.get("tags").isJsonArray()) {
            for (var e : obj.getAsJsonArray("tags")) {
                JsonObject t = e.getAsJsonObject();
                if (t.has("name") && !t.get("name").isJsonNull() && !t.get("name").getAsString().isBlank()) {
                    JsonObject clean = new JsonObject();
                    clean.addProperty("name", t.get("name").getAsString().trim());
                    clean.addProperty("confidence", t.has("confidence") && !t.get("confidence").isJsonNull()
                            ? t.get("confidence").getAsDouble() : 0);
                    out.add(clean);
                }
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("category", category);
        result.add("tags", out);
        return result.toString();
    }

    private static String extractJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("LLM 输出缺少 JSON 片段: " + content);
        }
        return content.substring(start, end + 1);
    }

    private static String toDataUrl(Path framePath) throws Exception {
        byte[] bytes = Files.readAllBytes(framePath);
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes);
    }
}
