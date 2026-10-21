package cn.diaojiahong.myyoutube.content.support;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ContentSubmissionRulesTest {
    final ContentSubmissionRules rules = new ContentSubmissionRules("content-moderation/blocked.txt");
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {
            "赌博", "赌\u200b博", " 赌博 ", "裸聊加微信", "网赌充值送彩金", "出售个人信息数据库",
            "盗\u200b号接单加微信", "  代考替考包过  ", "同城约炮", "网赌平台", "usdt跑分",
            "冰毒交易", "k粉", "办假证", "银行卡四件套", "社工库", "刷单返佣", "论文代写", "出售器官"
    })
    void rejectsExactBlockedTermsWithFieldOnlyErrors(String text) {
        assertEquals("标题包含不允许的内容，请修改", assertThrows(BizException.class, () -> rules.title(text)).getMessage());
        assertEquals("简介包含不允许的内容，请修改", assertThrows(BizException.class, () -> rules.description(text)).getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "奉劝大家不要赌博", "警方打击出售个人信息的行为", "警惕网赌充值送彩金骗局",
            "不要相信代考替考包过", "个人信息保护", "禁毒宣传：冰毒危害", "警方打击网络赌博",
            "论文代写违反学术规范", "警惕刷单返利骗局", "如何保护个人信息", "珍爱生命，远离毒品"
    })
    void contextualTextContinuesToExistingReview(String text) {
        assertEquals(text, rules.title(text));
        assertEquals(text, rules.description(text));
    }

    @Test void preservesUserTextAndExistingWhitespaceContractWithoutHotSearchQualityLimits() {
        String title = "反诈 ＡＢＣ\u200b  ２０２６";
        String description = "简介 Ｃ＋＋\u200b  中文\n原始第二行";
        assertEquals(title, rules.title(title));
        assertEquals(description, rules.description(description));
        assertEquals("标题", rules.title(" 标题 "));
        assertNull(rules.description(null));
        assertNull(rules.description("  "));
        assertEquals("字".repeat(100), rules.title("字".repeat(100)));
        assertEquals("字".repeat(512), rules.description("字".repeat(512)));
        assertEquals("1111", rules.title("1111"));
        assertEquals("!!!", rules.title("!!!"));
        assertThrows(BizException.class, () -> rules.title("字".repeat(101)));
        assertThrows(BizException.class, () -> rules.description("字".repeat(513)));
    }

    @Test void customPhraseRulesNormalizeTemporaryCopyAndDoNotJoinFields() throws Exception {
        Path file = directory.resolve("custom.txt");
        Files.writeString(file, "exact: ＡＢＣ １２３\nphrase: 禁止短语\nphrase: 跨字段\n");
        var custom = new ContentSubmissionRules(file.toUri().toString());
        assertThrows(BizException.class, () -> custom.title("abc 123"));
        assertThrows(BizException.class, () -> custom.description("简介中禁止\u200b短语"));
        assertEquals("ＡＢＣ １２３ 教程", custom.title("ＡＢＣ １２３ 教程"));
        assertEquals("跨字", custom.title("跨字"));
        assertEquals("段", custom.description("段"));
    }

    @Test void springLoadsDefaultDictionaryAndRejectsInvalidConfiguredDictionaries() throws Exception {
        var context = new ApplicationContextRunner().withUserConfiguration(Config.class);
        context.run(c -> {
            assertNull(c.getStartupFailure());
            assertThrows(BizException.class, () -> c.getBean(ContentSubmissionRules.class).title("裸聊加微信"));
        });
        Path file = directory.resolve("invalid.txt");
        Files.writeString(file, "exact:valid\nunknown:private-rule");
        for (String location : new String[]{file.toUri().toString(), directory.resolve("missing.txt").toUri().toString(), ""}) {
            context.withPropertyValues("my-youtube.content-moderation.blocked-location=" + location).run(c -> {
                assertNotNull(c.getStartupFailure());
                Throwable cause = c.getStartupFailure();
                while (cause.getCause() != null) cause = cause.getCause();
                assertFalse(cause.getMessage().contains("private-rule"));
            });
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ContentSubmissionRules.class)
    static class Config {}
}
