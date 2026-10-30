package cn.diaojiahong.myyoutube.search;

import cn.diaojiahong.myyoutube.common.media.AudioDiscoveryContracts;
import cn.diaojiahong.myyoutube.search.domain.doc.AudioDoc;
import cn.diaojiahong.myyoutube.search.service.AudioSearchService;
import cn.diaojiahong.myyoutube.video.api.AudioDocumentPort;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchClients;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import java.util.List;
import org.springframework.data.elasticsearch.core.IndexOperations;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "content.integration", matches = "true")
class LiveAudioSearchTest {
    ElasticsearchClient client;
    AudioDocumentPort source;
    AudioSearchService service;

    @BeforeEach
    void setup() throws Exception {
        client = ElasticsearchClients.createImperative(ClientConfiguration.builder()
                .connectedTo(System.getProperty("content.es.address", "127.0.0.1:19201")).build());
        assertEquals("content-it", client.info().clusterName());
        var operations = new ElasticsearchTemplate(client);
        IndexOperations index = operations.indexOps(AudioDoc.class);
        if (index.exists()) index.delete();
        source = mock(AudioDocumentPort.class);
        service = new AudioSearchService(client, source, operations);
    }

    @AfterEach
    void close() throws Exception {
        if (client != null) client._transport().close();
    }

    AudioDiscoveryContracts.Document document(
            long id,
            long version,
            String visibility,
            boolean deleted,
            long createdAt) {
        return new AudioDiscoveryContracts.Document(
                id,
                9L,
                "雨声音频",
                "ready",
                "approved",
                visibility,
                deleted,
                version,
                createdAt);
    }

    void rebuild(AudioDiscoveryContracts.Document document) {
        when(source.document(document.id())).thenReturn(document);
        service.rebuild(document.id());
    }

    @Test
    void annotatedMappingKeepsTypesAnalyzersSettingsAndWireFields() throws Exception {
        rebuild(document(1, 1, "public", false, 1000));
        Map<String,Property> fields = client.indices()
                .getMapping(r -> r.index("audio"))
                .result()
                .get("audio")
                .mappings()
                .properties();
        for (String field : List.of("id", "uploaderId", "version", "createdAt")) assertTrue(fields.get(field).isLong());
        for (String field : List.of("visibility", "processingStatus", "reviewStatus")) assertTrue(fields.get(field)
                .isKeyword());
        assertTrue(fields.get("deleted").isBoolean());
        assertEquals("ik_max_word", fields.get("title").text().analyzer());
        assertEquals("ik_smart", fields.get("title").text().searchAnalyzer());
        IndexSettings settings = client.indices()
                .getSettings(r -> r.index("audio"))
                .result()
                .get("audio")
                .settings()
                .index();
        assertEquals("1", settings.numberOfShards());
        assertEquals("0", settings.numberOfReplicas());
        GetResponse<AudioDoc> saved = client.get(r -> r.index("audio").id("1"), AudioDoc.class);
        assertEquals(1L, saved.version());
        assertEquals(9L, saved.source().getUploaderId());
        verify(source).acknowledge(new AudioDiscoveryContracts.Ack(1L, 1));
    }

    @Test
    void privateAndDeletedVersionsRejectDelayedPublicWrites() throws Exception {
        AudioDiscoveryContracts.Document old = document(1, 1, "public", false, 1000);
        rebuild(old);
        rebuild(document(1, 2, "private", false, 1000));
        rebuild(old);
        assertEquals("private", client.get(r -> r.index("audio").id("1"), AudioDoc.class).source().getVisibility());
        assertTrue(service.search(new AudioDiscoveryContracts.Query("雨声", null, 1, 10)).ids().isEmpty());
        assertEquals(List.of(1L), service.search(new AudioDiscoveryContracts.Query("雨声", 9L, 1, 10)).ids());
        rebuild(document(1, 3, "private", true, 1000));
        rebuild(old);
        assertTrue(client.get(r -> r.index("audio").id("1"), AudioDoc.class).source().isDeleted());
        assertTrue(service.search(new AudioDiscoveryContracts.Query(null, 9L, 1, 10)).ids().isEmpty());
    }

    @Test
    void extraCandidateAndStableOrderingKeepPublicAndOwnerScopesSeparate() {
        rebuild(document(1, 1, "public", false, 1000));
        rebuild(document(2, 1, "public", false, 1000));
        rebuild(document(3, 1, "private", false, 2000));
        rebuild(document(4, 1, "public", true, 3000));
        AudioDiscoveryContracts.Slice first = service.search(new AudioDiscoveryContracts.Query("雨声", null, 1, 1));
        assertEquals(List.of(2L), first.ids());
        assertTrue(first.hasMore());
        AudioDiscoveryContracts.Slice second = service.search(new AudioDiscoveryContracts.Query("雨声", null, 2, 1));
        assertEquals(List.of(1L), second.ids());
        assertFalse(second.hasMore());
        assertEquals(List.of(3L, 2L, 1L), service.search(new AudioDiscoveryContracts.Query("雨声", 9L, 1, 10)).ids());
    }
}
