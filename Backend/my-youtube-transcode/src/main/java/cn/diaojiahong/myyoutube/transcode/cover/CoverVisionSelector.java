package cn.diaojiahong.myyoutube.transcode.cover;

import cn.diaojiahong.myyoutube.common.prompt.PromptTemplates;
import cn.diaojiahong.myyoutube.common.resilience.ModelCallGuard;
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
 * 失败交给任务调度器统一重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoverVisionSelector {

    private final LlmPort llmPort;
    private final ModelCallGuard guard;
    private final CoverProperties properties;

    private final PromptTemplates prompts;

    public Integer pickBest(List<FFmpegUtil.FrameScore> candidates) {
        if (!properties.isVisionEnabled()) return null;
        try {
            List<LlmPort.ContentPart> parts = new ArrayList<>();
            parts.add(new LlmPort.ContentPart("text",
                    prompts.snapshot()
                            .render("cover-user", java.util.Map.of("candidateCount", candidates.size())), null));
            for (FFmpegUtil.FrameScore candidate : candidates) {
                parts.add(new LlmPort.ContentPart("image_url", null,
                        new LlmPort.ContentPart.ImageUrl(toDataUrl(candidate.framePath()))));
            }
            LlmPort.ChatRequest request = new LlmPort.ChatRequest(
                    List.of(new LlmPort.Message("user", null, parts)),
                    properties.effectiveVisionModel(), 0.2, 512);
            String content = guard.call("LLM", () -> llmPort.chat(request)).getContent();
            return parseIndex(content, candidates.size());
        } catch (cn.diaojiahong.myyoutube.common.resilience.ModelCallFailure e) {
            throw e;
        } catch (cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("封面模型选帧失败", e);
        }
    }

    /** @return 0-based 下标; 越界/解析失败抛异常 */
    private Integer parseIndex(String content, int candidateCount) {
        try {
            String json = extractJson(content);
            JsonObject obj = JsonUtil.GSON.fromJson(json, JsonObject.class);
            int index = obj.get("index").getAsInt();
            if (index < 1 || index > candidateCount) {
                log.warn("LLM 选帧下标越界: index={}, 候选数={}", index, candidateCount);
                throw new IllegalArgumentException("封面选帧下标越界");
            }
            return index - 1;
        } catch (Exception e) {
            throw new IllegalArgumentException("封面选帧输出不可解析", e);
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
