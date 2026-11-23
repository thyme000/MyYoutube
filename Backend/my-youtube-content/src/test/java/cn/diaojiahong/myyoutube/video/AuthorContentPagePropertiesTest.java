package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.video.config.AuthorContentPageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class AuthorContentPagePropertiesTest {
    @Test
    void bindsRefillLimits() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("my-youtube.author-content-page.scan-budget-ms", "100")
                .withProperty("my-youtube.author-content-page.scan-limit", "300");
        AuthorContentPageProperties properties = Binder.get(environment).bind("my-youtube.author-content-page",
                Bindable.of(AuthorContentPageProperties.class)).get();
        assertDoesNotThrow(properties::validate);
        assertEquals(100, properties.getScanBudgetMs());
        assertEquals(300, properties.getScanLimit());
    }

    @Test
    void rejectsUnboundedOrInvalidScanSettings() {
        AuthorContentPageProperties properties = new AuthorContentPageProperties();
        properties.setScanLimit(0);
        assertThrows(IllegalStateException.class, properties::validate);
        properties.setScanLimit(10);
        properties.setScanBudgetMs(0);
        assertThrows(IllegalStateException.class, properties::validate);
    }
}
