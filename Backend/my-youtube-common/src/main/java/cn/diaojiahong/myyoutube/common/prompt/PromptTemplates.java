package cn.diaojiahong.myyoutube.common.prompt;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class PromptTemplates {
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([a-zA-Z][a-zA-Z0-9]*)}}");
    private static final Map<String, Set<String>> VARIABLES = Map.of(
            "summary-system", Set.of("summaryLimit", "chapterRule"),
            "summary-no-chapters", Set.of(),
            "tagging-system", Set.of("categories", "tagPool"),
            "cover-user", Set.of("candidateCount"),
            "review-system", Set.of(),
            "hot-search-group-system", Set.of());
    private volatile Snapshot current;

    public PromptTemplates() {
        try (var input = new ClassPathResource("prompts/model-prompts.yaml").getInputStream()) {
            current = parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("无法加载默认模型提示词", e);
        }
    }

    public Snapshot snapshot() { return current; }

    public synchronized void replace(String yaml) {
        current = parse(yaml);
    }

    private static Snapshot parse(String source) {
        if (source == null || source.isBlank() || source.length() > 131072)
            throw new IllegalArgumentException("提示词配置为空或超过 131072 字符限制");
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(131072);
        Object loaded = new Yaml(new SafeConstructor(options)).load(source);
        if (!(loaded instanceof Map<?, ?> root) || !root.keySet().equals(Set.of("templates"))
                || !(root.get("templates") instanceof Map<?, ?> templates) || !templates.keySet().equals(VARIABLES.keySet()))
            throw new IllegalArgumentException("提示词配置必须包含完整且已知的 templates");
        Map<String, String> validated = new TreeMap<>();
        for (var entry : VARIABLES.entrySet()) {
            if (!(templates.get(entry.getKey()) instanceof String text) || text.isBlank() || text.length() > 16384)
                throw new IllegalArgumentException("提示词为空或过长: " + entry.getKey());
            var matcher = VARIABLE.matcher(text);
            Set<String> actual = new HashSet<>();
            while (matcher.find()) actual.add(matcher.group(1));
            String remainder = VARIABLE.matcher(text).replaceAll("");
            if (!actual.equals(entry.getValue()) || remainder.contains("{{"))
                throw new IllegalArgumentException("提示词变量不匹配: " + entry.getKey());
            validated.put(entry.getKey(), text);
        }
        return new Snapshot(validated);
    }

    public static final class Snapshot {
        private final Map<String, String> templates;
        private final String version;

        private Snapshot(Map<String, String> templates) {
            this.templates = Map.copyOf(templates);
            try {
                var digest = MessageDigest.getInstance("SHA-256");
                templates.forEach((key, value) -> digest.update((key + ":" + value.length() + ":" + value).getBytes(StandardCharsets.UTF_8)));
                version = HexFormat.of().formatHex(digest.digest());
            } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        }

        public String version() { return version; }
        public String render(String name) { return render(name, Map.of()); }
        public String render(String name, Map<String, ?> values) {
            String template = templates.get(name);
            if (template == null || !values.keySet().equals(VARIABLES.get(name)) || values.values().stream().anyMatch(Objects::isNull))
                throw new IllegalArgumentException("提示词或渲染变量不匹配: " + name);
            var matcher = VARIABLE.matcher(template);
            var result = new StringBuilder();
            while (matcher.find()) matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(values.get(matcher.group(1)).toString()));
            matcher.appendTail(result);
            return result.toString();
        }
    }
}
