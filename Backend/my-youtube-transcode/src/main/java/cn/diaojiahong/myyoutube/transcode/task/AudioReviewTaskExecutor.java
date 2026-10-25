package cn.diaojiahong.myyoutube.transcode.task;

import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.prompt.PromptTemplates;
import cn.diaojiahong.myyoutube.common.resilience.*;
import cn.diaojiahong.myyoutube.common.text.TextRuleMatcher;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.*;

@Component
public class AudioReviewTaskExecutor implements MediaTaskExecutor {
    private final ContentArtifactStore artifacts;
    private final LlmPort llm;
    private final ModelCallGuard guard;
    private final PromptTemplates prompts;
    private final AudioAnalysisProperties properties;
    private final TextRuleMatcher rules;
    public AudioReviewTaskExecutor(ContentArtifactStore artifacts, LlmPort llm, ModelCallGuard guard, PromptTemplates prompts,
            AudioAnalysisProperties properties, @Value("${my-youtube.content-moderation.blocked-location:content-moderation/blocked.txt}") String location) throws IOException {
        this.artifacts = artifacts; this.llm = llm; this.guard = guard; this.prompts = prompts; this.properties = properties;
        rules = TextRuleMatcher.load(location);
    }
    @Override public MediaTaskType type() { return MediaTaskType.AUDIO_REVIEW; }
    @Override public String execute(MediaTaskMessage task) {
        var input = task.getAudioAnalysisInput(); input.validate();
        if (input.type() != type() || !input.audioId().equals(task.getOwnerId()) || !input.userId().equals(task.getUserId()))
            throw new IllegalArgumentException("Audio review task mismatch");
        var transcript = artifacts.read(input.transcriptKey(), ContentArtifacts.Transcript.class);
        AudioTranscript.validate(transcript, input.durationMs());
        int count = 0;
        boolean blocked = rules.matches(input.title());
        for (var segment : transcript.segments()) {
            count += (int) segment.text().codePoints().filter(Character::isLetterOrDigit).count();
            blocked |= rules.matches(segment.text());
        }
        String plain = transcript.segments().stream().map(ContentArtifacts.Segment::text).collect(java.util.stream.Collectors.joining(" "));
        blocked |= rules.matches(plain);
        var verdicts = new ArrayList<ReviewVerdict>();
        if (count >= properties.getMinimumCharacters()) {
            var snapshot = prompts.snapshot();
            String evidence = EvidenceText.full(input.title(), null, transcript, new ContentArtifacts.Ocr(List.of()));
            for (String chunk : EvidenceText.chunks(evidence, properties.getChunkCharacters())) {
                var request = new LlmPort.ChatRequest(List.of(LlmPort.Message.asText("system", snapshot.render("review-system")),
                        LlmPort.Message.asText("user", chunk)), properties.getModel(), 0.0, 1500);
                request.setResponseFormat(ReviewTaskExecutor.responseFormat());
                verdicts.add(guard.call("LLM", () -> {
                    var response = llm.chat(request);
                    if (response == null || !"stop".equals(response.getFinishReason())) throw new ModelCallFailure("LLM", "音频初筛输出未正常结束", null);
                    try { return ReviewVerdict.parse(response.getContent(), response.getModel(), snapshot.version()); }
                    catch (IllegalArgumentException error) { throw new ModelCallFailure("LLM", "音频初筛输出结构无效", error); }
                }));
            }
        }
        return JsonUtil.toJson(new AudioAnalysisContracts.ScreenResult(input.audioId(), input.objectKey(), input.transcriptKey(), count,
                verdicts.isEmpty(), blocked, List.copyOf(verdicts)));
    }
}
