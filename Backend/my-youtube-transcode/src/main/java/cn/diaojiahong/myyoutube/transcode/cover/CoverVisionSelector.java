package cn.diaojiahong.myyoutube.transcode.cover;

import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import cn.diaojiahong.myyoutube.transcode.ffmpeg.FFmpegUtil;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * LLM 封面选帧: 候选帧按顺序内嵌 base64 送视觉模型, 返回入选帧下标。
 * 输出契约是序列号而非时间戳, 时间戳由调用方按下标反查, 避免模型幻觉时间点。
 * 任何失败(未配模型/调用异常/输出不合法)返回 null, 交给启发式兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoverVisionSelector {

    private static final String PROMPT_TEMPLATE = """
            你是视频封面挑选助手。下面按顺序给出同一段视频的 %d 张候选帧, 编号 1~%d。
            挑选最适合作为视频封面的一张: 画面主体清晰、构图完整、信息量足, 适合在信息流小图尺寸展示。
            只输出 JSON: {"index": 编号}, 不要输出任何其他文字。""";

    private final LlmPort llmPort;
    private final CoverProperties properties;

    public Integer pickBest(List<FFmpegUtil.FrameScore> candidates) {
        try {
            List<LlmPort.ContentPart> parts = new ArrayList<>();
            parts.add(new LlmPort.ContentPart("text",
                    PROMPT_TEMPLATE.formatted(candidates.size(), candidates.size()), null));
            for (FFmpegUtil.FrameScore candidate : candidates) {
                parts.add(new LlmPort.ContentPart("image_url", null,
                        new LlmPort.ContentPart.ImageUrl(toDataUrl(candidate.framePath()))));
            }
            LlmPort.ChatRequest request = new LlmPort.ChatRequest(
                    List.of(new LlmPort.Message("user", null, parts)),
                    properties.effectiveVisionModel(), 0.2, 512);
            String content = llmPort.chat(request).getContent();
            return parseIndex(content, candidates.size());
        } catch (Exception e) {
            log.warn("LLM 封面选帧失败, 回落启发式: {}", e.getMessage());
            return null;
        }
    }

    /** @return 0-based 下标; 越界/解析失败返回 null */
    private Integer parseIndex(String content, int candidateCount) {
        try {
            String json = extractJson(content);
            JsonObject obj = JsonUtil.GSON.fromJson(json, JsonObject.class);
            int index = obj.get("index").getAsInt();
            if (index < 1 || index > candidateCount) {
                log.warn("LLM 选帧下标越界: index={}, 候选数={}", index, candidateCount);
                return null;
            }
            return index - 1;
        } catch (Exception e) {
            log.warn("LLM 选帧输出不可解析: {}", content);
            return null;
        }
    }

    /** 剥掉模型可能包裹的 markdown 代码块/闲话, 取首个花括号段 */
    private static String extractJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("缺少 JSON 片段");
        }
        return content.substring(start, end + 1);
    }

    private static String toDataUrl(Path framePath) throws Exception {
        byte[] bytes = Files.readAllBytes(framePath);
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes);
    }
}
