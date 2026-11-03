package cn.diaojiahong.myyoutube.common.media;

import cn.diaojiahong.myyoutube.security.PlaybackTokens.Scope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ContentRoutesTest {
    @Test
    void generatedPathsPreserveThePublicHttpContract() {
        assertEquals("/contents/123/streams/master.m3u8", ContentRoutes.master(Scope.VIEW, 123));
        assertEquals("/admin/content/123/streams/master.m3u8", ContentRoutes.master(Scope.REVIEW, 123));
        assertEquals("/contents/123/streams/720/index.m3u8", ContentRoutes.rendition(Scope.VIEW, 123, 720));
        assertEquals("/admin/content/123/streams/0/index.m3u8", ContentRoutes.rendition(Scope.REVIEW, 123, 0));
        assertEquals("720/index.m3u8", ContentRoutes.relativeRendition(720));
    }

    @ParameterizedTest
    @EnumSource(Scope.class)
    void generatedMasterAndRenditionPathsHaveTheCorrectAuthorizationScope(Scope scope) {
        for (String path : new String[] {ContentRoutes.master(scope, 123),
                ContentRoutes.rendition(scope, 123, 720), ContentRoutes.rendition(scope, 123, 0)}) {
            ContentRoutes.Resource resource = ContentRoutes.match(path);
            assertNotNull(resource);
            assertEquals("123", resource.contentId());
            assertEquals(scope, resource.scope());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/contents/0/streams/master.m3u8", "/contents/01/streams/master.m3u8",
            "/contents/123/streams/720/media.mp4", "/contents/123/streams/index.m3u8",
            "/contents/123/streams/masterXm3u8", "/contents/123/streams/-1/index.m3u8",
            "/admin/content/123/streams/720/indexXm3u8", "/other/123/streams/master.m3u8",
            "/contents/123/streams/master.m3u8/extra"
    })
    void nonPlaybackPathsAreNotClassifiedAsPlaybackResources(String path) {
        assertNull(ContentRoutes.match(path));
    }

    @Test
    void oversizedIdsRemainStringsForSafeComparisonWithTheVerifiedGrant() {
        ContentRoutes.Resource resource = ContentRoutes.match(
                "/contents/9223372036854775808/streams/master.m3u8");
        assertNotNull(resource);
        assertEquals("9223372036854775808", resource.contentId());
    }
}
