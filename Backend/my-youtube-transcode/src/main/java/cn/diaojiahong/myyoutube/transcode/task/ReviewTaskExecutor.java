package cn.diaojiahong.myyoutube.transcode.task;

import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.resilience.ModelCallGuard;
import cn.diaojiahong.myyoutube.common.utils.*;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import cn.diaojiahong.myyoutube.video.api.ContentPipelinePort;
import cn.diaojiahong.myyoutube.transcode.domain.enums.MediaTaskType;
import com.google.gson.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
@RequiredArgsConstructor
public class ReviewTaskExecutor implements MediaTaskExecutor {

    private static final String VISION_DISABLED_REASON = "画面审核未开启，仅审核文本证据";

    private final ContentPipelinePort video;
    private final ContentArtifactStore artifacts;
    private final S3StorageUtil storage;
    private final LlmPort llm;
    private final ModelCallGuard guard;

    @Override
    public MediaTaskType type() {
        return MediaTaskType.REVIEW;
    }

    @Override
    public String execute(MediaTaskMessage message) {
        var policy = video.reviewSpec(message.getPipelineId(), message.getAttempt());
        if (!policy.enabled()) return "{\"cancelled\":true}";

        var asrPointer = StructuredJson.object(message.getDependencies().get("ASR"));
        var ocrPointer = StructuredJson.object(message.getDependencies().get("OCR"));
        var summaryPointer = StructuredJson.object(message.getDependencies().get("SUMMARY"));
        var transcript = artifacts.read(asrPointer.get("segmentsKey").getAsString(), ContentArtifacts.Transcript.class);
        var ocr = artifacts.read(ocrPointer.get("artifactKey").getAsString(), ContentArtifacts.Ocr.class);
        var summary = artifacts.read(summaryPointer.get("artifactKey").getAsString(), ContentArtifacts.Summary.class);
        var input = message.getInput();
        String evidence = EvidenceText.full(input.title(), input.description(), transcript, ocr) + "\n摘要：" + summary.summary();

        var verdicts = new ArrayList<ReviewVerdict>();
        var batches = new JsonArray();
        for (String chunk : EvidenceText.chunks(evidence, policy.chunkCharacters())) {
            var verdict = call(policy, List.of(new LlmPort.ContentPart("text", chunk, null)));
            verdicts.add(verdict);
            addBatch(batches, "TEXT", verdict);
        }

        if (policy.visionEnabled()) {
            reviewFrames(policy, input, ocr, summary, verdicts, batches);
        } else {
            var skipped = new JsonObject();
            skipped.addProperty("source", "FRAMES");
            skipped.addProperty("skipped", true);
            skipped.addProperty("reason", VISION_DISABLED_REASON);
            batches.add(skipped);
        }

        return aggregate(policy, verdicts, batches);
    }

    private void reviewFrames(PipelineContracts.ReviewSpec policy, PipelineContracts.Input input, ContentArtifacts.Ocr ocr,
                              ContentArtifacts.Summary summary, List<ReviewVerdict> verdicts, JsonArray batches) {
        int expected = input.media().dynamic() == null ? 8 : input.media().dynamic().items().size();
        if (ocr.frames().size() != expected) throw new IllegalArgumentException("Missing review frames");
        if (input.media().dynamic() != null) {
            for (int i = 0; i < expected; i++) {
                var image = input.media().dynamic().items().get(i);
                var frame = ocr.frames().get(i);
                if (frame.imageIndex() != i || frame.timeMs() != image.startMs() || !frame.objectKey().equals(image.imageObjectKey()))
                    throw new IllegalArgumentException("图集审核图片与输入不一致");
            }
        }

        for (int start = 0; start < ocr.frames().size(); start += policy.frameBatchSize()) {
            var parts = new ArrayList<LlmPort.ContentPart>();
            parts.add(new LlmPort.ContentPart("text", "内容摘要：" + summary.summary(), null));

            int end = Math.min(start + policy.frameBatchSize(), ocr.frames().size());
            for (var frame : ocr.frames().subList(start, end)) {
                parts.add(new LlmPort.ContentPart("text", "画面位置 " + frame.timeMs() + "ms", null));
                parts.add(new LlmPort.ContentPart("image_url", null,
                        new LlmPort.ContentPart.ImageUrl(storage.signGetUrl(frame.objectKey(), 600))));
            }

            var verdict = call(policy, parts);
            verdicts.add(verdict);
            addBatch(batches, "FRAMES", verdict);
        }
    }

    private String aggregate(PipelineContracts.ReviewSpec policy, List<ReviewVerdict> verdicts, JsonArray batches) {
        String decision = verdicts.stream().anyMatch(v -> "REJECT".equals(v.decision())) ? "REJECT"
                : verdicts.stream().allMatch(v -> "PASS".equals(v.decision())) ? "PASS" : "UNCERTAIN";
        String reason = verdicts.stream().filter(v -> !v.passes(policy.maxRisk(), policy.minConfidence())).map(ReviewVerdict::reason)
                .findFirst().orElse(policy.visionEnabled() ? "全部证据批次通过机器初审" : "全部文本证据批次通过机器初审");

        return JsonUtil.toJson(new ReviewVerdict(decision, verdicts.stream().mapToDouble(ReviewVerdict::riskScore).max().orElseThrow(),
                verdicts.stream().mapToDouble(ReviewVerdict::confidence).min().orElseThrow(), reason, batches,
                verdicts.stream().map(ReviewVerdict::model).distinct().collect(java.util.stream.Collectors.joining(",")), policy.rulesVersion()));
    }

    private ReviewVerdict call(PipelineContracts.ReviewSpec policy, List<LlmPort.ContentPart> parts) {
        var response = guard.call("LLM", () -> llm.chat(new LlmPort.ChatRequest(List.of(LlmPort.Message.asText("system", policy.rules()),
                new LlmPort.Message("user", null, parts)), policy.model(), 0.0, 1500)));

        return ReviewVerdict.parse(response.getContent(), response.getModel() == null ? policy.model() : response.getModel());
    }

    private void addBatch(JsonArray batches, String source, ReviewVerdict verdict) {
        var batch = JsonUtil.GSON.toJsonTree(verdict).getAsJsonObject();
        batch.addProperty("batch", batches.size() + 1);
        batch.addProperty("source", source);
        batches.add(batch);
    }
}
