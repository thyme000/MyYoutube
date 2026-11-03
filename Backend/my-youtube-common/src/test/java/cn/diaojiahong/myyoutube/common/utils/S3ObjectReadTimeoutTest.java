package cn.diaojiahong.myyoutube.common.utils;

import cn.diaojiahong.myyoutube.common.properties.S3Properties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class S3ObjectReadTimeoutTest {
    @Test
    void defaultReadKeepsTheClientDefaultTimeoutConfiguration() {
        S3Properties properties = new S3Properties();
        properties.setBucketName("resource-test");
        S3Client client = mock(S3Client.class);
        byte[] bytes = new byte[] {1, 2, 3};
        when(client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), bytes));
        S3StorageUtil storage = new S3StorageUtil(properties, client, null);

        assertArrayEquals(bytes, storage.readObject("media/playlist.m3u8"));

        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(client).getObjectAsBytes(request.capture());
        assertEquals("resource-test", request.getValue().bucket());
        assertEquals("media/playlist.m3u8", request.getValue().key());
        assertTrue(request.getValue().overrideConfiguration().isEmpty());
    }

    @Test
    void bothReadOverloadsPreserveTheOriginalSdkException() {
        S3Client client = mock(S3Client.class);
        SdkClientException failure = SdkClientException.create("connection failure");
        when(client.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow(failure);
        S3StorageUtil storage = new S3StorageUtil(new S3Properties(), client, null);

        assertSame(failure, assertThrows(SdkClientException.class, () -> storage.readObject("media/playlist.m3u8")));
        assertSame(failure, assertThrows(SdkClientException.class,
                () -> storage.readObject("media/playlist.m3u8", Duration.ofSeconds(10))));
    }

    @Test
    void resourceReadCarriesTheWholeApiCallTimeoutAndCorrectObjectIdentity() {
        S3Properties properties = new S3Properties();
        properties.setBucketName("resource-test");
        S3Client client = mock(S3Client.class);
        byte[] bytes = new byte[] {1, 2, 3};
        when(client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), bytes));
        S3StorageUtil storage = new S3StorageUtil(properties, client, null);

        assertArrayEquals(bytes, storage.readObject("media/playlist.m3u8", Duration.ofSeconds(10)));

        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(client).getObjectAsBytes(request.capture());
        assertEquals("resource-test", request.getValue().bucket());
        assertEquals("media/playlist.m3u8", request.getValue().key());
        assertEquals(Duration.ofSeconds(10), request.getValue().overrideConfiguration().orElseThrow()
                .apiCallTimeout().orElseThrow());
    }
}
