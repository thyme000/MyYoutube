package cn.diaojiahong.myyoutube.common.text;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class TextRuleMatcherTest {
    @TempDir Path directory;

    @Test void normalizesBothRulesAndInputWithoutRewritingInput() throws Exception {
        var matcher = load("# comment\n\nexact: Ｃ＋＋  教程\nphrase: 猫\u200b  咪\nphrase: a.b\nphrase: 𠮷猫\n");
        assertTrue(matcher.matches("c++   教程"));
        assertFalse(matcher.matches("更多 C++ 教程"));
        assertTrue(matcher.matches("可爱猫   咪视频"));
        assertTrue(matcher.matches("learn A.B now"));
        assertFalse(matcher.matches("learn axb now"));
        assertTrue(matcher.matches("认识𠮷猫"));
        assertFalse(matcher.matches(null));
    }

    @Test void handlesFailureLinksSuffixOutputsAndLargeSharedPrefixes() throws Exception {
        var random = new Random(42);
        var phrases = new LinkedHashSet<String>();
        phrases.addAll(java.util.List.of("abcde", "bc", "bce", "abx", "aby", "aab"));
        for (int i = 0; i < 300; i++) phrases.add("abc" + letters(random, 6));
        var matcher = load("phrase:" + String.join("\nphrase:", phrases));
        var entries = phrases.toArray(String[]::new);
        for (int i = 0; i < 1000; i++) {
            String text = letters(random, 40);
            if (i % 2 == 0) text = text.substring(0, 20) + entries[random.nextInt(entries.length)] + text.substring(20);
            String query = text;
            assertEquals(phrases.stream().anyMatch(query::contains), matcher.matches(text), text);
        }
        for (String text : new String[]{"abc", "abce", "zabx", "zaby", "aaaab"}) assertTrue(matcher.matches(text));
        assertFalse(matcher.matches("abz"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown:private-rule", "missing-prefix", "exact:", "phrase:   ", "phrase:\u200b"})
    void rejectsInvalidWholeDictionaryWithLineNumberOnly(String invalid) throws Exception {
        var error = assertThrows(IllegalArgumentException.class, () -> load("exact:valid\n" + invalid));
        assertEquals("词表第 2 行格式错误或词条为空", error.getMessage());
    }

    @Test void rejectsMissingFilesInvalidUtf8AndBlankLocations() throws Exception {
        assertThrows(IOException.class, () -> TextRuleMatcher.load(directory.resolve("missing.txt").toUri().toString()));
        for (String location : new String[]{null, "", " "})
            assertThrows(IllegalArgumentException.class, () -> TextRuleMatcher.load(location));
        Path file = directory.resolve("invalid.txt");
        Files.writeString(file, "exact:valid\n");
        Files.write(file, new byte[]{(byte) 0xc3, (byte) 0x28}, StandardOpenOption.APPEND);
        assertThrows(IOException.class, () -> TextRuleMatcher.load(file.toUri().toString()));
    }

    @Test void emptyDictionaryDoesNotMatchAnyText() throws Exception {
        var matcher = load("# empty\n");
        assertFalse(matcher.matches(""));
        assertFalse(matcher.matches("valid"));
        assertFalse(TextRuleMatcher.empty().matches("valid"));
    }

    private TextRuleMatcher load(String rules) throws IOException {
        Path file = directory.resolve("rules.txt");
        Files.writeString(file, rules);
        return TextRuleMatcher.load(file.toUri().toString());
    }

    private static String letters(Random random, int length) {
        var text = new StringBuilder(length);
        for (int i = 0; i < length; i++) text.append((char) ('a' + random.nextInt(4)));
        return text.toString();
    }
}
