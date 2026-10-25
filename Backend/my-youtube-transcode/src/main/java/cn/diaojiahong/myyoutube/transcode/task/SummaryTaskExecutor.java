package cn.diaojiahong.myyoutube.transcode.task;

import cn.diaojiahong.myyoutube.common.prompt.PromptTemplates;
import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import cn.diaojiahong.myyoutube.common.media.MediaTaskType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class SummaryTaskExecutor implements MediaTaskExecutor {
    private final ContentArtifactStore artifacts;
    private final cn.diaojiahong.myyoutube.transcode.config.PipelineProperties properties;
    private final LlmPort llm;
    private final cn.diaojiahong.myyoutube.common.resilience.ModelCallGuard guard;
    private final PromptTemplates prompts;
    @Override public MediaTaskType type() { return MediaTaskType.SUMMARY; }
    @Override public String execute(MediaTaskMessage msg) {
        var input = msg.getInput();
        long startedAt = System.currentTimeMillis();
        var asr = StructuredJson.object(msg.getDependencies().get("ASR"));
        var ocr = StructuredJson.object(msg.getDependencies().get("OCR"));
        var transcript = artifacts.read(asr.get("segmentsKey").getAsString(), ContentArtifacts.Transcript.class);
        var frames = artifacts.read(ocr.get("artifactKey").getAsString(), ContentArtifacts.Ocr.class);
        if (EvidenceText.empty(input.title(), input.description(), transcript, frames)) {
            log.info("Summary completed pipelineId={} taskId={} attempt={} reason=NO_TEXT_EVIDENCE summaryLength=0 elapsedMs={}",
                    msg.getPipelineId(), msg.getTaskId(), msg.getAttempt(), System.currentTimeMillis() - startedAt);
            return JsonUtil.toJson(Map.of("artifactKey", artifacts.write(msg.getPipelineId(), "SUMMARY", msg.getAttempt(),
                    new ContentArtifacts.Summary("", List.of()))));
        }
        var templates = prompts.snapshot();
        String evidence = EvidenceText.full(input.title(), input.description(), transcript, frames);
        List<String> summaries = new ArrayList<>();
        List<ContentArtifacts.Chapter> chapters = new ArrayList<>();
        int chunks = 0;
        for (String chunk : EvidenceText.chunks(evidence, properties.getChunkCharacters())) {
            chunks++;
            boolean timedText = transcript.segments().stream().anyMatch(segment -> !segment.text().isBlank())
                    || frames.frames().stream().anyMatch(frame -> !frame.text().isBlank());
            var result = summarize(chunk, input.media().video() != null && timedText, templates);
            if (!result.summary().isEmpty()) summaries.add(result.summary());
            for (var chapter : timedText && input.media().video() != null ? result.chapters() : List.<ContentArtifacts.Chapter>of()) {
                if (chapter.startMs() < 0 || chapter.startMs() > ocr.get("durationMs").getAsLong() || chapter.title() == null || chapter.title().isBlank())
                    throw new IllegalArgumentException("章节位置非法");
                chapters.add(chapter);
            }
        }
        int mergeRounds = 0;
        while (summaries.size() > 1) {
            mergeRounds++;
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
            reduced.removeIf(String::isEmpty);
            if (reduced.size() >= summaries.size()) throw new IllegalStateException("摘要合并未收敛");
            summaries = reduced;
        }
        String summary = summaries.isEmpty() ? "" : summaries.get(0);
        var result = new ContentArtifacts.Summary(summary, summary.isEmpty() ? List.of() : chapters.stream()
                .sorted(Comparator.comparingLong(ContentArtifacts.Chapter::startMs)).distinct().toList());
        log.info("Summary completed pipelineId={} taskId={} attempt={} evidenceChars={} chunks={} mergeRounds={} chapters={} summaryLength={} elapsedMs={}",
                msg.getPipelineId(), msg.getTaskId(), msg.getAttempt(), evidence.length(), chunks, mergeRounds,
                result.chapters().size(), result.summary().length(), System.currentTimeMillis() - startedAt);
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
        var json = StructuredJson.strictObject(response.getContent());
        if (json.has("summary") && json.get("summary").isJsonPrimitive()
                && json.getAsJsonPrimitive("summary").isString() && json.get("summary").getAsString().isEmpty()
                && json.has("chapters") && json.get("chapters").isJsonArray() && !json.getAsJsonArray("chapters").isEmpty()) {
            log.info("Summary chapters discarded reason=EMPTY_SUMMARY count={}", json.getAsJsonArray("chapters").size());
            json.add("chapters", new com.google.gson.JsonArray());
        }
        validateStructure(json, allowChapters);
        var result = JsonUtil.fromJson(json.toString(), ContentArtifacts.Summary.class);
        if (result == null || result.summary() == null || result.chapters() == null)
            throw new IllegalArgumentException("摘要输出不完整");
        String summary = result.summary();
        boolean whitespaceOnly = !summary.isEmpty() && summary.isBlank();
        if (summary.length() > summaryLimit || whitespaceOnly) throw new IllegalArgumentException("摘要长度或内容非法");
        return result;
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
                                "summary", Map.of("type", "string", "minLength", 0, "maxLength", limit), "chapters", array))));
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
