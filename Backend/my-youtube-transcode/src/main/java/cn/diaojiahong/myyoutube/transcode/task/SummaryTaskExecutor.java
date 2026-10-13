package cn.diaojiahong.myyoutube.transcode.task;

import cn.diaojiahong.myyoutube.common.prompt.PromptTemplates;
import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import cn.diaojiahong.myyoutube.transcode.domain.enums.MediaTaskType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SummaryTaskExecutor implements MediaTaskExecutor {
    private final ContentArtifactStore artifacts;
    private final cn.diaojiahong.myyoutube.transcode.config.PipelineProperties properties;
    private final LlmPort llm;
    private final cn.diaojiahong.myyoutube.common.resilience.ModelCallGuard guard;
    private final PromptTemplates prompts;
    @Override public MediaTaskType type() { return MediaTaskType.SUMMARY; }
    @Override public String execute(MediaTaskMessage msg) {
        var input = msg.getInput();
        var asr = StructuredJson.object(msg.getDependencies().get("ASR"));
        var ocr = StructuredJson.object(msg.getDependencies().get("OCR"));
        var transcript = artifacts.read(asr.get("segmentsKey").getAsString(), ContentArtifacts.Transcript.class);
        var frames = artifacts.read(ocr.get("artifactKey").getAsString(), ContentArtifacts.Ocr.class);
        if (EvidenceText.empty(input.title(), input.description(), transcript, frames))
            return JsonUtil.toJson(Map.of("artifactKey", artifacts.write(msg.getPipelineId(), "SUMMARY", msg.getAttempt(),
                    new ContentArtifacts.Summary("", List.of()))));
        var templates = prompts.snapshot();
        String evidence = EvidenceText.full(input.title(), input.description(), transcript, frames);
        List<String> summaries = new ArrayList<>();
        List<ContentArtifacts.Chapter> chapters = new ArrayList<>();
        for (String chunk : EvidenceText.chunks(evidence, properties.getChunkCharacters())) {
            boolean timedText = transcript.segments().stream().anyMatch(segment -> !segment.text().isBlank())
                    || frames.frames().stream().anyMatch(frame -> !frame.text().isBlank());
            var result = summarize(chunk, input.media().video() != null && timedText, templates);
            summaries.add(result.summary());
            for (var chapter : timedText && input.media().video() != null ? result.chapters() : List.<ContentArtifacts.Chapter>of()) {
                if (chapter.startMs() < 0 || chapter.startMs() > ocr.get("durationMs").getAsLong() || chapter.title() == null || chapter.title().isBlank())
                    throw new IllegalArgumentException("章节位置非法");
                chapters.add(chapter);
            }
        }
        while (summaries.size() > 1) {
            List<String> reduced = new ArrayList<>();
            var group = new ArrayList<String>();
            int characters = 0;
            for (String summary : summaries) {
                if (!group.isEmpty() && characters + 1 + summary.length() > properties.getChunkCharacters()) {
                    reduced.add(group.size() == 1 ? group.get(0) : summarize(String.join("\n", group), false, templates).summary());
                    group.clear(); characters = 0;
                }
                group.add(summary); characters += summary.length() + 1;
            }
            reduced.add(group.size() == 1 ? group.get(0) : summarize(String.join("\n", group), false, templates).summary());
            if (reduced.size() >= summaries.size()) throw new IllegalStateException("摘要合并未收敛");
            summaries = reduced;
        }
        var result = new ContentArtifacts.Summary(summaries.get(0), chapters.stream()
                .sorted(Comparator.comparingLong(ContentArtifacts.Chapter::startMs)).distinct().toList());
        return JsonUtil.toJson(Map.of("artifactKey", artifacts.write(msg.getPipelineId(), "SUMMARY", msg.getAttempt(), result)));
    }
    private ContentArtifacts.Summary summarize(String evidence, boolean allowChapters, PromptTemplates.Snapshot templates) {
        int summaryLimit = Math.min(600, properties.getChunkCharacters() / 3);
        String instruction = templates.render("summary-system", Map.of("summaryLimit", summaryLimit,
                "chapterRule", allowChapters ? "" : templates.render("summary-no-chapters")));
        var request = new LlmPort.ChatRequest(List.of(LlmPort.Message.asText("system", instruction),
                LlmPort.Message.asText("user", evidence)), null, 0.1, 1500);
        request.setResponseFormat(responseFormat(summaryLimit, allowChapters));
        var response = guard.call("LLM", () -> llm.chat(request));
        if (response == null || !"stop".equals(response.getFinishReason()))
            throw new IllegalArgumentException("摘要未正常结束");
        var json = strictObject(response.getContent());
        validateStructure(json, allowChapters);
        var result = JsonUtil.fromJson(json.toString(), ContentArtifacts.Summary.class);
        if (result == null || result.summary() == null || result.summary().isBlank() || result.summary().length() > summaryLimit || result.chapters() == null)
            throw new IllegalArgumentException("摘要输出不完整");
        return result;
    }

    private static com.google.gson.JsonObject strictObject(String content) {
        if (content == null) throw new IllegalArgumentException("摘要输出为空");
        try {
            var factory = com.fasterxml.jackson.core.JsonFactory.builder()
                    .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper(factory)
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            var node = mapper.readTree(content);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("摘要须为 JSON 对象");
            return JsonUtil.GSON.fromJson(node.toString(), com.google.gson.JsonObject.class);
        } catch (java.io.IOException e) { throw new IllegalArgumentException("摘要 JSON 非法"); }
    }

    private static Map<String, Object> responseFormat(int limit, boolean chapters) {
        var chapter = Map.of("type", "object", "additionalProperties", false,
                "required", List.of("startMs", "title"), "properties", Map.of(
                        "startMs", Map.of("type", "integer", "minimum", 0),
                        "title", Map.of("type", "string", "minLength", 1)));
        var array = new java.util.HashMap<String, Object>();
        array.put("type", "array"); array.put("items", chapter);
        if (!chapters) array.put("maxItems", 0);
        return Map.of("type", "json_schema", "json_schema", Map.of("name", "content_summary", "strict", true,
                "schema", Map.of("type", "object", "additionalProperties", false,
                        "required", List.of("summary", "chapters"), "properties", Map.of(
                                "summary", Map.of("type", "string", "minLength", 1, "maxLength", limit), "chapters", array))));
    }

    private static void validateStructure(com.google.gson.JsonObject json, boolean chapters) {
        if (!json.keySet().equals(java.util.Set.of("summary", "chapters"))
                || !json.get("summary").isJsonPrimitive() || !json.getAsJsonPrimitive("summary").isString()
                || !json.get("chapters").isJsonArray()) throw new IllegalArgumentException("摘要结构非法");
        if (!chapters && !json.getAsJsonArray("chapters").isEmpty()) throw new IllegalArgumentException("本阶段禁止章节");
        for (var element : json.getAsJsonArray("chapters")) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("章节结构非法");
            var chapter = element.getAsJsonObject();
            if (!chapter.keySet().equals(java.util.Set.of("startMs", "title"))
                    || !chapter.get("startMs").isJsonPrimitive() || !chapter.getAsJsonPrimitive("startMs").isNumber()
                    || !chapter.get("title").isJsonPrimitive() || !chapter.getAsJsonPrimitive("title").isString()
                    || chapter.get("title").getAsString().isBlank()) throw new IllegalArgumentException("章节结构非法");
            chapter.get("startMs").getAsBigDecimal().longValueExact();
        }
    }
}
