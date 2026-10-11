package cn.diaojiahong.myyoutube.recommend.auto;
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
    private static final String PROMPT_CATEGORIES = "GAME(游戏) MOVIE(影视) MUSIC(音乐) KNOWLEDGE(知识) LIFE(生活) TECH(科技) ENTERTAINMENT(娱乐) OTHER(其他)";
    private final LlmPort llmPort;
    private final TagPoolHolder tagPoolHolder;
    private final ModelCallGuard guard;
    private final TaggingProperties properties;
    public String generate(TaggingInput input) {
        if (!properties.isVisionEnabled()) input = TaggingInput.ofText(input.text());
        if ((input.text() == null || input.text().isBlank()) && (input.images() == null || input.images().isEmpty()))
            return "{\"category\":\"OTHER\",\"tags\":[]}";
        var request = new LlmPort.ChatRequest(buildMessages(input), null, 0.1, 512);
        var response = guard.call("LLM", () -> llmPort.chat(request));
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
        return """
                你是内容平台的自动打标器。任务: 判断内容所属类目, 并挑选标签。
                规则:
                1. 类目只能从【类目表】中选一个, 用括号外的 CODE 返回;
                2. 标签优先从【候选标签】中选择, 每个标签给出 0~1 的置信度;
                3. 候选确实都不贴切时可自创新标签, 最多 2 个, 每个 2~6 个字, 是规范名词, 禁止摘抄标题片段;
                4. 标签总数控制在 3~5 个;
                5. 只输出 JSON, 不要任何解释, 格式: {"category":"CODE","tags":[{"name":"标签名","confidence":0.9}]}
                【类目表】""" + PROMPT_CATEGORIES
                + "\n【候选标签】\n" + pool;
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
