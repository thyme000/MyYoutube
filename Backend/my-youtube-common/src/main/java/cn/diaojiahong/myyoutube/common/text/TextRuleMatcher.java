package cn.diaojiahong.myyoutube.common.text;

import org.springframework.core.io.DefaultResourceLoader;

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
import java.util.Set;
import java.util.regex.Pattern;

public final class TextRuleMatcher {
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cc}\\p{Cf}]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final Set<String> exact;
    private final Node root = new Node();

    private TextRuleMatcher(Set<String> exact, Set<String> phrases) {
        this.exact = Set.copyOf(exact);
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
        // 失败链接复用最长后缀，扫描时不必逐条遍历短语。
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

    public static TextRuleMatcher empty() {
        return new TextRuleMatcher(Set.of(), Set.of());
    }

    public static TextRuleMatcher load(String location) throws IOException {
        if (location == null || location.isBlank()) throw new IllegalArgumentException("词表路径不能为空");
        var exact = new HashSet<String>();
        var phrases = new HashSet<String>();
        try (var reader = new BufferedReader(new InputStreamReader(
                new DefaultResourceLoader().getResource(location).getInputStream(), StandardCharsets.UTF_8.newDecoder()))) {
            int number = 0;
            for (String line; (line = reader.readLine()) != null;) {
                number++;
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int separator = line.indexOf(':');
                String type = separator < 0 ? "" : line.substring(0, separator);
                String value = separator < 0 ? "" : normalize(line.substring(separator + 1));
                if (value.isEmpty() || !(type.equals("exact") || type.equals("phrase")))
                    throw new IllegalArgumentException("词表第 " + number + " 行格式错误或词条为空");
                if (type.equals("exact")) exact.add(value);
                else phrases.add(value);
            }
        }
        return new TextRuleMatcher(exact, phrases);
    }

    public static String normalize(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return WHITESPACE.matcher(CONTROL_CHARACTERS.matcher(normalized).replaceAll("").strip()).replaceAll(" ");
    }

    public boolean matches(String text) {
        if (text == null) return false;
        String normalized = normalize(text);
        if (exact.contains(normalized)) return true;
        Node node = root;
        for (int i = 0; i < normalized.length(); i++) {
            node = advance(node, normalized.charAt(i));
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
