package cn.diaojiahong.myyoutube.search.hot;

import cn.diaojiahong.myyoutube.common.prompt.PromptTemplates;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import cn.diaojiahong.myyoutube.llm.api.QueryEmbeddingPort;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import feign.Request;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class HotSearchModel {
    private static final Map<String, Object> GROUP_RESPONSE_FORMAT = Map.of("type", "json_schema", "json_schema",
            Map.of("name", "hot_search_groups", "strict", true, "schema",
                    Map.of("type", "object", "additionalProperties", false, "required", List.of("groups"),
                            "properties", Map.of("groups", Map.of("type", "array", "items",
                                    Map.of("type", "object", "additionalProperties", false, "required", List.of("name", "members"),
                                            "properties", Map.of("name", Map.of("type", "string"),
                                                    "members", Map.of("type", "array", "items", Map.of("type", "integer")))))))));

    public record Grouping(String name, List<String> keywords) {
        public Grouping { keywords = List.copyOf(keywords); }
    }

    private final LlmPort llm;
    private final QueryEmbeddingPort queries;
    private final HotSearchProperties properties;
    private final PromptTemplates prompts;

    public QueryEmbeddingPort.Info info() {
        QueryEmbeddingPort.Info info = queries.info(new Request.Options(1000, TimeUnit.MILLISECONDS, 1000, TimeUnit.MILLISECONDS, true));
        if (info == null || info.version() == null || info.version().isBlank() || info.dims() <= 0) {
            throw new IllegalStateException("模型向量版本信息非法: " + info);
        }
        return info;
    }

    public Map<String, List<Float>> vectors(List<String> texts, QueryEmbeddingPort.Info info) {
        if (texts.isEmpty()) return Map.of();
        var response = llm.embeddings(new LlmPort.EmbeddingsRequest(texts), options());
        if (response == null || !info.version().equals(response.getVersion()) || response.getDims() == null
                || response.getDims() != info.dims() || response.getItems() == null) {
            throw new IllegalStateException("热搜向量响应结构或版本不匹配: batchSize=" + texts.size()
                    + " expectedVersion=" + info.version() + " expectedDims=" + info.dims()
                    + " actualVersion=" + (response == null ? null : response.getVersion())
                    + " actualDims=" + (response == null ? null : response.getDims()));
        }
        Map<Integer, List<Float>> accepted = new HashMap<>();
        Set<Integer> seen = new HashSet<>();
        int invalidItems = 0, duplicateItems = 0;
        for (var item : response.getItems()) {
            if (item == null || item.index() < 0 || item.index() >= texts.size()) {
                invalidItems++;
                continue;
            }
            if (!seen.add(item.index())) {
                duplicateItems++;
                accepted.remove(item.index());
                continue;
            }
            if (item.error() != null || !HotSearchGroup.validVector(item.vector(), info.dims())) {
                invalidItems++;
                continue;
            }
            accepted.put(item.index(), List.copyOf(item.vector()));
        }
        if (accepted.size() != texts.size() || invalidItems > 0 || duplicateItems > 0 || response.getUnavailableReason() != null) {
            log.warn("Hot search embeddings filtered batchSize={} responseItems={} accepted={} invalidItems={} duplicateItems={} version={} dims={} unavailable={}",
                    texts.size(), response.getItems().size(), accepted.size(), invalidItems, duplicateItems,
                    info.version(), info.dims(), response.getUnavailableReason() != null);
        }
        Map<String, List<Float>> result = new HashMap<>();
        accepted.forEach((index, vector) -> result.put(texts.get(index), vector));
        return result;
    }

    public List<Grouping> group(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        var templates = prompts.snapshot();
        try {
            var response = llm.chat(groupRequest(texts, templates), options());
            return parseGroups(groupItems(response), texts, templates.version());
        } catch (RuntimeException error) {
            throw new IllegalStateException("热搜分组失败: batchSize=" + texts.size() + " promptVersion=" + templates.version(), error);
        }
    }

    private LlmPort.ChatRequest groupRequest(List<String> texts, PromptTemplates.Snapshot templates) {
        List<Map<String, Object>> queries = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) queries.add(Map.of("index", i, "query", texts.get(i)));
        return new LlmPort.ChatRequest(List.of(
                LlmPort.Message.asText("system", templates.render("hot-search-group-system")),
                LlmPort.Message.asText("user", JsonUtil.toJson(Map.of("queries", queries)))), null, 0.0, 4096,
                GROUP_RESPONSE_FORMAT);
    }

    private JsonArray groupItems(LlmPort.ChatResponse response) {
        if (response == null || !"stop".equals(response.getFinishReason()) || response.getContent() == null) {
            throw new IllegalStateException("热搜分组未正常完成: finishReason=" + (response == null ? null : response.getFinishReason()));
        }
        var json = JsonParser.parseString(response.getContent()).getAsJsonObject();
        requireFields(json, Set.of("groups"));
        if (!json.get("groups").isJsonArray()) throw new IllegalStateException("热搜分组列表非法");
        return json.getAsJsonArray("groups");
    }

    private List<Grouping> parseGroups(JsonArray items, List<String> texts, String promptVersion) {
        Set<Integer> seen = new HashSet<>();
        List<Grouping> result = new ArrayList<>();
        int invalidGroups = 0, duplicateMembers = 0;
        for (int i = 0; i < items.size(); i++) {
            try {
                var value = items.get(i).getAsJsonObject();
                var group = parseGroup(value, texts, seen);
                duplicateMembers += value.getAsJsonArray("members").size() - group.keywords().size();
                if (!group.keywords().isEmpty()) result.add(group);
            } catch (RuntimeException error) {
                invalidGroups++;
                log.warn("Hot search group discarded groupIndex={} batchSize={} promptVersion={} reason={}",
                        i, texts.size(), promptVersion, error.getClass().getSimpleName());
            }
        }
        if (invalidGroups > 0 || duplicateMembers > 0 || seen.size() != texts.size())
            log.warn("Hot search groups filtered batchSize={} responseGroups={} acceptedGroups={} invalidGroups={} duplicateMembers={} unassignedMembers={} promptVersion={}",
                    texts.size(), items.size(), result.size(), invalidGroups, duplicateMembers, texts.size() - seen.size(), promptVersion);
        return List.copyOf(result);
    }

    private Grouping parseGroup(JsonObject value, List<String> texts, Set<Integer> seen) {
        requireFields(value, Set.of("name", "members"));
        if (!value.get("name").isJsonPrimitive() || !value.getAsJsonPrimitive("name").isString()
                || !value.get("members").isJsonArray()) {
            throw new IllegalStateException("热搜分组内容非法");
        }
        var indices = memberIndices(value.getAsJsonArray("members"), texts.size());
        List<String> members = new ArrayList<>();
        for (int index : indices) {
            if (seen.add(index)) members.add(texts.get(index));
        }
        String name = value.get("name").getAsString();
        // 实测模型会偶尔翻译中文；此时保留组内中文原词，避免改变检索语言。
        if (!hasHan(name) && name.codePoints().anyMatch(Character::isLetter))
            name = members.stream().filter(HotSearchModel::hasHan).findFirst().orElse(name);
        return new Grouping(name, members);
    }

    private List<Integer> memberIndices(JsonArray members, int inputSize) {
        if (members.isEmpty()) throw new IllegalStateException("热搜分组成员为空");
        List<Integer> indices = new ArrayList<>();
        for (var member : members) {
            if (!member.isJsonPrimitive() || !member.getAsJsonPrimitive().isNumber())
                throw new IllegalStateException("热搜分组编号必须为整数");
            int index = member.getAsBigDecimal().intValueExact();
            if (index < 0 || index >= inputSize) throw new IllegalStateException("热搜分组编号越界");
            indices.add(index);
        }
        return indices;
    }

    private static void requireFields(JsonObject value, Set<String> fields) {
        if (!value.keySet().equals(fields)) throw new IllegalStateException("热搜分组字段非法");
    }

    private static boolean hasHan(String text) {
        return text.codePoints().anyMatch(value -> Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN);
    }

    private Request.Options options() {
        return new Request.Options(1, TimeUnit.SECONDS, properties.getModelTimeoutSeconds(), TimeUnit.SECONDS, true);
    }
}
