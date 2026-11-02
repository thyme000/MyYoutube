package cn.diaojiahong.myyoutube.common.utils;

import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.properties.S3Properties;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentMatchers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.HeadObjectPresignRequest;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class S3ReadSigningTest {
    @Test
    void realSdkSignsGetAndHeadSeparatelyWithoutBindingRange() {
        S3Properties properties = new S3Properties();
        properties.setBucketName("playback-test");
        try (S3Presigner signer = S3Presigner.builder().endpointOverride(URI.create("http://127.0.0.1:9000"))
                .region(Region.US_EAST_1)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test-secret")))
                .build()) {
            S3StorageUtil storage = new S3StorageUtil(properties, null, signer);
            String key = "media-prepared/9/101/media file.mp4";
            String get = storage.presignReadUrl(key, 1200, SdkHttpMethod.GET);
            String head = storage.presignReadUrl(key, 1200, SdkHttpMethod.HEAD);
            assertNotEquals(get, head, "HEAD 不能复用 GET 的签名");
            for (String url : java.util.List.of(get, head)) {
                URI uri = URI.create(url);
                assertEquals("/playback-test/" + key, uri.getPath());
                String query = URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8);
                assertTrue(query.contains("X-Amz-Expires=1200"));
                assertTrue(query.contains("X-Amz-SignedHeaders=host"));
                assertFalse(query.toLowerCase().contains("range"), "客户端可按需请求不同字节范围");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = SdkHttpMethod.class, names = {"GET", "HEAD"})
    void failedSigningPropagatesDependencyFailureInsteadOfReturningObjectKey(SdkHttpMethod method) {
        S3Presigner signer = mock(S3Presigner.class);
        SdkClientException failure = SdkClientException.create("signing unavailable");
        when(signer.presignGetObject(ArgumentMatchers.<Consumer<GetObjectPresignRequest.Builder>>any())).thenThrow(failure);
        when(signer.presignHeadObject(ArgumentMatchers.<Consumer<HeadObjectPresignRequest.Builder>>any())).thenThrow(failure);
        S3StorageUtil storage = new S3StorageUtil(new S3Properties(), null, signer);

        DependencyException error = assertThrows(DependencyException.class,
                () -> storage.presignReadUrl("media-prepared/9/101/media.mp4", 1200, method));
        assertEquals(ErrorCode.DEPENDENCY_FAILURE, error.getErrorCode());
        assertSame(failure, error.getCause());
    }
}
