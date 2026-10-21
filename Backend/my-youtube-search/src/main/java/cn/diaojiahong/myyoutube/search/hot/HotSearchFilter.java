package cn.diaojiahong.myyoutube.search.hot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Component
public class HotSearchFilter {
    private static final Pattern REPEATED_DIGITS = Pattern.compile("([0-9])\\1{3,}");
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cc}\\p{Cf}]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final HotSearchProperties properties;
    private final Set<String> exact;
    private final PhraseMatcher phrases;

    public HotSearchFilter(HotSearchProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
        Set<String> loadedExact = new HashSet<>();
        Set<String> loadedPhrases = new HashSet<>();
        String location = properties.getExcludedLocation();
        try {
            if (location == null || location.isBlank()) throw new IllegalArgumentException("热搜排除词路径不能为空");
            try (var reader = new BufferedReader(new InputStreamReader(
                    new DefaultResourceLoader().getResource(location).getInputStream(), StandardCharsets.UTF_8.newDecoder()))) {
                for (String line; (line = reader.readLine()) != null;) {
                    line = line.strip();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int separator = line.indexOf(':');
                    String type = separator < 0 ? "" : line.substring(0, separator);
                    String value = separator < 0 ? "" : fold(line.substring(separator + 1));
                    if (value.isEmpty()) throw new IllegalArgumentException("热搜排除词条不能为空");
                    if (type.equals("exact")) loadedExact.add(value);
                    else if (type.equals("phrase")) loadedPhrases.add(value);
                    else throw new IllegalArgumentException("热搜排除词条格式错误");
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            loadedExact.clear();
            loadedPhrases.clear();
            log.warn("热搜排除词加载失败，使用空词表，location={}", location, e);
        }
        exact = Set.copyOf(loadedExact);
        phrases = new PhraseMatcher(loadedPhrases);
    }

    public String eligible(String keyword) {
        if (keyword == null) return null;
        String text = keyword.strip();
        if (text.codePointCount(0, text.length()) > properties.getMaxLength()) return null;
        String folded = fold(text);
        if (folded.isEmpty() || folded.codePoints().noneMatch(Character::isLetterOrDigit)
                || REPEATED_DIGITS.matcher(folded).matches() || exact.contains(folded)
                || phrases.containsMatch(folded)) return null;
        return text;
    }

    private static String fold(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return WHITESPACE.matcher(CONTROL_CHARACTERS.matcher(normalized).replaceAll("").strip()).replaceAll(" ");
    }

    private static final class PhraseMatcher {
        private final Node root = new Node();

        PhraseMatcher(Set<String> phrases) {
            for (String phrase : phrases) {
                Node node = root;
                for (int i = 0; i < phrase.length(); i++) {
                    node = node.children.computeIfAbsent(phrase.charAt(i), ignored -> new Node());
                }
                node.matched = true;
            }
            var queue = new ArrayDeque<Node>();
            for (Node child : root.children.values()) {
                child.failure = root;
                queue.add(child);
            }
            // Aho-Corasick 失败链接复用最长后缀，避免每次查询逐条扫描短语。
            while (!queue.isEmpty()) {
                Node node = queue.remove();
                for (var entry : node.children.entrySet()) {
                    Node child = entry.getValue();
                    child.failure = advance(node.failure, entry.getKey());
                    child.matched |= child.failure.matched;
                    queue.add(child);
                }
            }
        }

        boolean containsMatch(String text) {
            Node node = root;
            for (int i = 0; i < text.length(); i++) {
                node = advance(node, text.charAt(i));
                if (node.matched) return true;
            }
            return false;
        }

        private Node advance(Node node, char character) {
            while (node != root && !node.children.containsKey(character)) node = node.failure;
            return node.children.getOrDefault(character, root);
        }

        private static final class Node {
            final Map<Character, Node> children = new HashMap<>();
            Node failure;
            boolean matched;
        }
    }
}
