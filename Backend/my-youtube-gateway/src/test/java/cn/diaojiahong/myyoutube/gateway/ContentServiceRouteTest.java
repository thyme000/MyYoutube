package cn.diaojiahong.myyoutube.gateway;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentServiceRouteTest {

    @Test
    void publicContentPathsForwardToContentService() throws Exception {
        Map<String, Object> root;
        try (InputStream input = ContentServiceRouteTest.class.getResourceAsStream("/application.yml")) {
            assertNotNull(input);
            Yaml yaml = new Yaml();
            root = yaml.load(input);
        }

        Map<String, Object> spring = childMap(root, "spring");
        Map<String, Object> cloud = childMap(spring, "cloud");
        Map<String, Object> gateway = childMap(cloud, "gateway");
        Map<String, Object> server = childMap(gateway, "server");
        Map<String, Object> webflux = childMap(server, "webflux");
        List<Map<String, Object>> routes = childList(webflux, "routes");

        Map<String, Object> contentRoute = null;
        for (Map<String, Object> route : routes) {
            assertFalse("video-service".equals(route.get("id")));
            if ("content-service".equals(route.get("id"))) {
                contentRoute = route;
            }
        }

        assertNotNull(contentRoute);
        assertEquals("lb://my-youtube-content-service", contentRoute.get("uri"));
        String predicates = String.valueOf(contentRoute.get("predicates"));
        assertTrue(predicates.contains("/contents/**"), predicates);
        assertTrue(predicates.contains("/audios/**"), predicates);
        assertFalse(predicates.contains("/video-tag/**"), predicates);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> childMap(Map<String, Object> source, String key) {
        return (Map<String, Object>) source.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> childList(Map<String, Object> source, String key) {
        return (List<Map<String, Object>>) source.get(key);
    }
}
