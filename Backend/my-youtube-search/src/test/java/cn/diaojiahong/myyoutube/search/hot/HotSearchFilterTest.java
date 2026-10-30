package cn.diaojiahong.myyoutube.search.hot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class HotSearchFilterTest {
    final HotSearchFilter filter = new HotSearchFilter(new HotSearchProperties());

    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\u200b", ".,.,", "!!!", "1111", "０００００", "😀😀"})
    void rejectsNoiseAndExcludedQueries(String text) {
        assertNull(filter.eligible(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {"12306", "2026", "1984", "C++", "C#", "猫", "𠮷", "1111教程", "Java 入门", "重置qwertyuiop键盘", "asdfghjkl", "QWERTYUIOP",
            "奉劝大家不要赌博", "警惕网赌骗局", "个人信息保护", "网络安全防护", "如何防范跑分兼职骗局"})
    void preservesUsefulNumbersSymbolsAndShortQueries(String text) {
        assertEquals(text, filter.eligible(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {"约炮", "网赌", "社工库", "地下赌场", "洗钱跑分", "冰毒交易", "假币交易", "开盒接单",
            "寻找同城约炮加微信", "全新网赌充值送彩金入口", "便宜个人信息数据库出售", "盗\u200b号接单加微信", "代考替考包过"})
    void rejectsExistingAndExpandedRiskTerms(String text) {
        assertNull(filter.eligible(text));
    }

    @Test
    void checksUnicodeLengthWithoutTruncatingOrRewritingQueries() {
        assertNull(filter.eligible(null));
        assertNull(filter.eligible("猫".repeat(65)));
        assertEquals("𠮷".repeat(64), filter.eligible("𠮷".repeat(64)));
        assertEquals("C++  教程", filter.eligible("  C++  教程  "));
    }

    @Test
    void validatesFiniteThresholdAndCapacity() {
        var properties = new HotSearchProperties();
        properties.setSimilarityThreshold(Double.NaN);
        assertThrows(IllegalArgumentException.class, properties::validate);
        properties.setSimilarityThreshold(.9);
        properties.setBatchSize(0);
        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    @Test
    void rejectsNullPropertiesImmediately() {
        NullPointerException error = assertThrows(NullPointerException.class, () -> new HotSearchFilter(null));
        assertEquals("properties", error.getMessage());
    }

    @Test
    void loadsCustomFileAndNormalizesRulesAndQueriesConsistently() throws Exception {
        HotSearchFilter custom = fromRules("# 自定义词表\n\nexact: Ｃ＋＋  教程\nphrase: 猫\u200b  咪\nphrase: a.b\nphrase: 𠮷猫\n");
        assertNull(custom.eligible("c++   教程"));
        assertEquals("更多 C++ 教程", custom.eligible("更多 C++ 教程"));
        assertNull(custom.eligible("可爱猫   咪视频"));
        assertNull(custom.eligible("learn A.B now"));
        assertEquals("learn axb now", custom.eligible("learn axb now"));
        assertNull(custom.eligible("认识𠮷猫"));
    }

    @Test
    void matchesOverlappingPrefixesFailureLinksAndSuffixOutputs() throws Exception {
        HotSearchFilter custom = fromRules("phrase: abcde\nphrase: bc\nphrase: bce\nphrase: abx\nphrase: aby\nphrase: aab\n");
        for (String text : new String[]{"abc", "abce", "zabx", "zaby", "aaaab"}) {
            assertNull(custom.eligible(text), text);
        }
        assertEquals("abz", custom.eligible("abz"));
    }

    @Test
    void matchesTheContainsOracleForALargeSharedPrefixDictionary() throws Exception {
        var random = new Random(42);
        var phrases = new LinkedHashSet<String>();
        for (int i = 0; i < 300; i++) phrases.add("abc" + randomLetters(random, 6));
        HotSearchFilter custom = fromRules("phrase:" + String.join("\nphrase:", phrases));
        String[] entries = phrases.toArray(String[]::new);
        for (int i = 0; i < 1000; i++) {
            String text = randomLetters(random, 40);
            if (i % 2 == 0) text = text.substring(0, 20) + entries[random.nextInt(entries.length)] + text.substring(20);
            String query = text;
            boolean excluded = phrases.stream().anyMatch(query::contains);
            assertEquals(excluded ? null : text, custom.eligible(text), text);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown:bad", "missing-prefix", "exact:", "phrase:   ", "phrase:\u200b"})
    void discardsAllRulesWhenAnyEntryIsInvalid(String invalid) throws Exception {
        HotSearchFilter custom = fromRules("exact:customword\nphrase:blocked\n" + invalid + "\n");
        assertEquals("customword", custom.eligible("customword"));
        assertEquals("someblockedtext", custom.eligible("someblockedtext"));
        assertNull(custom.eligible("1111"));
        assertNull(custom.eligible("!!!"));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void missingFileWarnsAndFallsBackToEmptyRules(CapturedOutput output) {
        var properties = new HotSearchProperties();
        properties.setExcludedLocation(directory.resolve("missing.txt").toUri().toString());
        HotSearchFilter custom = assertDoesNotThrow(() -> new HotSearchFilter(properties));
        assertEquals("asdfghjkl", custom.eligible("asdfghjkl"));
        assertNull(custom.eligible("0000"));
        assertTrue(output.getOut().contains("WARN"));
        assertTrue(output.getOut().contains("热搜排除词加载失败，使用空词表"));
    }

    @Test
    void invalidUtf8DiscardsPreviouslyLoadedRules() throws Exception {
        Path file = directory.resolve("invalid-utf8.txt");
        Files.writeString(file, "exact:customword\nphrase:blocked\n");
        Files.write(file, new byte[]{(byte) 0xc3, (byte) 0x28}, StandardOpenOption.APPEND);
        var properties = new HotSearchProperties();
        properties.setExcludedLocation(file.toUri().toString());
        HotSearchFilter custom = assertDoesNotThrow(() -> new HotSearchFilter(properties));
        assertEquals("customword", custom.eligible("customword"));
        assertEquals("someblockedtext", custom.eligible("someblockedtext"));
    }

    @Test
    void emptyFileAndMissingLocationsKeepBaseQualityRules() throws Exception {
        var properties = new HotSearchProperties();
        for (String location : new String[]{null, "", " "}) {
            properties.setExcludedLocation(location);
            HotSearchFilter custom = assertDoesNotThrow(() -> new HotSearchFilter(properties));
            assertEquals("Java", custom.eligible("Java"));
            assertNull(custom.eligible("!!!"));
        }
        HotSearchFilter custom = fromRules("# no exclusions\n\n");
        assertEquals("Java", custom.eligible("Java"));
        assertNull(custom.eligible("1111"));
    }

    private HotSearchFilter fromRules(String rules) throws Exception {
        Path file = directory.resolve("excluded.txt");
        Files.writeString(file, rules);
        var properties = new HotSearchProperties();
        properties.setExcludedLocation(file.toUri().toString());
        return new HotSearchFilter(properties);
    }

    private static String randomLetters(Random random, int length) {
        var text = new StringBuilder(length);
        for (int i = 0; i < length; i++) text.append((char) ('a' + random.nextInt(4)));
        return text.toString();
    }
}
