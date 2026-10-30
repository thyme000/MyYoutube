package cn.diaojiahong.myyoutube.common.utils;

import cn.diaojiahong.myyoutube.common.properties.S3Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.http.*;
import java.security.*;
import java.io.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class S3UploadSigningTest {
    @Test
    @EnabledIfSystemProperty(named = "storage.live-upload", matches = "true")
    void realVideoPutPreservesBytesAndRejectsSignedHeaderTampering() throws Exception {
        URI endpoint = URI.create(System.getenv("S3_ENDPOINT"));
        assertTrue(java.util.Set.of("127.0.0.1", "localhost").contains(endpoint.getHost()));
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(
                System.getenv("S3_ACCESS_KEY"), System.getenv("S3_SECRET_KEY")));
        var properties = new S3Properties();
        properties.setBucketName(System.getenv("S3_BUCKET_NAME"));
        Path source = Path.of(System.getProperty("preparation.video-source"));
        String key = "diagnostics/put-video/" + UUID.randomUUID() + ".mp4";
        HttpClient http = HttpClient.newHttpClient();
        try (S3Presigner signer = S3Presigner.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(credentials).build();
             S3Client client = S3Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .forcePathStyle(true).credentialsProvider(credentials).build()) {
            var storage = new S3StorageUtil(properties, client, signer);
            try {
                URI limited = URI.create(storage.presignPutUrl(key, 300, 3, "video/mp4"));
                for (String body : java.util.List.of("1234", "123")) {
                    HttpRequest request = HttpRequest.newBuilder(limited)
                            .header("Content-Type", body.length() == 3 ? "video/webm" : "video/mp4")
                            .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
                    assertEquals(403, http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
                }
                URI url = URI.create(storage.presignPutUrl(key, 300, Files.size(source), "video/mp4"));
                HttpRequest request = HttpRequest.newBuilder(url).header("Content-Type", "video/mp4")
                        .PUT(HttpRequest.BodyPublishers.ofFile(source)).build();
                assertEquals(200, http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
                assertEquals(
                        Files.size(source),
                        client.headObject(b -> b.bucket(properties.getBucketName()).key(key)).contentLength());
                try (ResponseInputStream<GetObjectResponse> stored = client.getObject(b -> b.bucket(properties.getBucketName()).key(key));
                     InputStream original = Files.newInputStream(source)) {
                    assertArrayEquals(digest(original), digest(stored));
                }
            } finally {
                client.deleteObject(b -> b.bucket(properties.getBucketName()).key(key));
            }
        }
    }

    private static byte[] digest(InputStream source) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        new DigestInputStream(source, digest).transferTo(OutputStream.nullOutputStream());
        return digest.digest();
    }

    @Test
    void videoPutSignatureLocksContentLengthAndType() {
        var properties = new S3Properties();
        properties.setBucketName("upload-test");
        try (S3Presigner signer = S3Presigner.builder().endpointOverride(URI.create("http://127.0.0.1:9000"))
                .region(Region.US_EAST_1)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        "test",
                        "test-secret")))
                .build()) {
            var storage = new S3StorageUtil(properties, null, signer);
            String url = storage.presignPutUrl("uploads/video/test.mp4", 300, 1234, "video/mp4");
            String query = URLDecoder.decode(URI.create(url).getRawQuery(), StandardCharsets.UTF_8);
            assertTrue(query.contains("X-Amz-SignedHeaders=content-length;content-type;host"));
            assertThrows(IllegalArgumentException.class,
                    () -> storage.presignPutUrl("test", 300, 0, "video/mp4"));
        }
    }
}
