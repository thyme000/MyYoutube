package cn.diaojiahong.myyoutube.recommend.auto;
import cn.diaojiahong.myyoutube.common.prompt.PromptTemplates;
import cn.diaojiahong.myyoutube.common.media.StructuredJson;
import cn.diaojiahong.myyoutube.common.resilience.ModelCallGuard;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import cn.diaojiahong.myyoutube.recommend.domain.po.Tag;
import cn.diaojiahong.myyoutube.recommend.tag.TagPoolHolder;
import cn.diaojiahong.myyoutube.video.api.enums.VideoCategoryEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
@RequiredArgsConstructor
public class TaggingService {
    private final LlmPort llmPort;
    private final TagPoolHolder tagPoolHolder;
    private final ModelCallGuard guard;
    private final TaggingProperties properties;
    private final PromptTemplates prompts;

    public String generate(TaggingInput input) {
        if (!properties.isVisionEnabled()) input = TaggingInput.ofText(input.text());
        if ((input.text() == null || input.text().isBlank()) && (input.images() == null || input.images().isEmpty()))
            return "{\"category\":\"OTHER\",\"tags\":[]}";
        var request = new LlmPort.ChatRequest(buildMessages(input), null, 0.1, 512);
        LlmPort.ChatResponse response = guard.call("LLM", () -> llmPort.chat(request));
        if (response == null) throw new IllegalArgumentException("Empty tag response");
        String json = StructuredJson.object(response.getContent()).toString();
        TagResultMapper.validate(json);
        return json;
    }

    private List<LlmPort.Message> buildMessages(TaggingInput input) {
        return List.of(
                LlmPort.Message.asText("system", buildSystemPrompt()),
                toUserMessage(input));
    }

    private LlmPort.Message toUserMessage(TaggingInput input) {
        if (input.images() == null || input.images().isEmpty()) {
            return LlmPort.Message.asText("user", input.text());
        }

        List<LlmPort.ContentPart> parts = new ArrayList<>();
        if (input.text() != null && !input.text().isBlank()) {
            parts.add(new LlmPort.ContentPart("text", input.text(), null));
        }
        for (TaggingInput.Image image : input.images()) {
            parts.add(new LlmPort.ContentPart("image_url", null, new LlmPort.ContentPart.ImageUrl(image.url())));
        }
        return new LlmPort.Message("user", null, parts);
    }

    private String buildSystemPrompt() {
        StringBuilder pool = new StringBuilder();
        Map<String, List<Tag>> grouped = tagPoolHolder.grouped();
        for (VideoCategoryEnum c : VideoCategoryEnum.values()) {
            appendPoolLine(pool, c.getLabel(), grouped.get(c.getCode()));
        }
        appendPoolLine(pool, "通用", grouped.get(""));
        String categories = Arrays.stream(VideoCategoryEnum.values())
                .map(category -> category.getCode() + "(" + category.getLabel() + ")")
                .collect(java.util.stream.Collectors.joining(" "));
        return prompts.snapshot()
                .render("tagging-system", Map.of("categories", categories, "tagPool", pool.toString()));
    }

    private void appendPoolLine(StringBuilder pool, String label, List<Tag> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }

        pool.append("- ").append(label).append(": ")
                .append(tags.stream().map(Tag::getName).collect(java.util.stream.Collectors.joining(", ")))
                .append('\n');
    }

}
