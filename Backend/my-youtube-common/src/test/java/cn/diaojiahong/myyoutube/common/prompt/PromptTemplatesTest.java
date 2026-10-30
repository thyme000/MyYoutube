package cn.diaojiahong.myyoutube.common.prompt;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class PromptTemplatesTest {
    static String defaults() throws Exception {
        return new ClassPathResource("prompts/model-prompts.yaml").getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void rendersJsonAndLiteralValuesWithoutRecursiveExpansion() {
        PromptTemplates.Snapshot snapshot = new PromptTemplates().snapshot();
        String pool = "美元$1、路径\\abc、{{categories}}";
        String rendered = snapshot.render("tagging-system", Map.of("categories", "TECH(科技)", "tagPool", pool));
        assertTrue(rendered.contains("\"category\":\"CODE\""));
        assertTrue(rendered.endsWith(pool));
        assertThrows(IllegalArgumentException.class, () -> snapshot.render("tagging-system", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> snapshot.render("unknown"));
    }

    @Test
    void updateIsAtomicAndExistingSnapshotKeepsItsVersion() throws Exception {
        var registry = new PromptTemplates();
        PromptTemplates.Snapshot before = registry.snapshot();
        registry.replace(defaults().replace("你是内容归纳器", "请精确归纳内容"));
        PromptTemplates.Snapshot after = registry.snapshot();
        assertNotEquals(before.version(), after.version());
        assertTrue(before.render("summary-system", Map.of("summaryLimit", 600, "chapterRule", ""))
                .startsWith("你是内容归纳器"));
        assertTrue(after.render("summary-system", Map.of("summaryLimit", 600, "chapterRule", ""))
                .startsWith("请精确归纳内容"));
        registry.replace(defaults());
        assertEquals(before.version(), registry.snapshot().version());
    }

    @Test
    void rejectsInvalidBundleAndRetainsEveryPreviousTemplate() throws Exception {
        var registry = new PromptTemplates();
        PromptTemplates.Snapshot before = registry.snapshot();
        String yaml = defaults();
        for (String invalid : List.of("", "templates: {}", "templates: [", yaml + "templates: {}\n",
                yaml.replace("{{summaryLimit}}", "{{typo}}"), yaml.replace("{{chapterRule}}", ""),
                yaml.replace("{{candidateCount}}", "{{candidateCount"),
                yaml.replace("cover-user:", "unknown:"), yaml + "extra: true\n",
                yaml.replace("summary-no-chapters: |-\n    本阶段 chapters 必须是空数组，不生成章节。", "summary-no-chapters: ''"),
                "templates: !!java.util.HashMap {}", "x".repeat(131073))) {
            assertThrows(RuntimeException.class, () -> registry.replace(invalid));
            assertSame(before, registry.snapshot());
        }
        assertThrows(IllegalArgumentException.class, () -> registry.replace(null));
    }

    @Test
    void concurrentReadersNeverSeeMixedTemplateVersions() throws Exception {
        var registry = new PromptTemplates();
        String old = defaults();
        String updated = old.replace("你是内容归纳器", "新版归纳器").replace("本阶段 chapters", "新版阶段 chapters");
        CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
            for (int i = 0; i < 100; i++) registry.replace(i % 2 == 0 ? updated : old);
        });
        for (int i = 0; i < 10000; i++) {
            PromptTemplates.Snapshot snapshot = registry.snapshot();
            String instruction = snapshot.render("summary-system", Map.of("summaryLimit", 600,
                    "chapterRule", snapshot.render("summary-no-chapters")));
            assertEquals(instruction.startsWith("新版"), instruction.contains("新版阶段"));
        }
        writer.join();
    }

    @Test
    void hotSearchGroupingRequiresItsTemplateAndRejectsVariables() throws Exception {
        var registry = new PromptTemplates();
        PromptTemplates.Snapshot before = registry.snapshot();
        String yaml = defaults();
        assertTrue(before.render("hot-search-group-system").contains("中文查询必须用中文"));
        String withoutHotSearch = yaml.substring(0, yaml.indexOf("  hot-search-group-system:"));
        assertThrows(IllegalArgumentException.class, () -> registry.replace(withoutHotSearch));
        assertSame(before, registry.snapshot());
        assertThrows(IllegalArgumentException.class,
                () -> registry.replace(yaml.replace("将本批搜索词按语义分组", "{{query}}将本批搜索词按语义分组")));
        assertSame(before, registry.snapshot());
    }
}
