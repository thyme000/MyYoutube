package cn.diaojiahong.myyoutube.common.utils;

import cn.diaojiahong.myyoutube.common.properties.S3Properties;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UploadStorageTest {
    private final S3Client client = mock(S3Client.class);
    private final S3StorageUtil storage = new S3StorageUtil(new S3Properties(), client, mock(S3Presigner.class));
    private final Duration timeout = Duration.ofSeconds(1);

    @Test void inspectionUsesTheExactKeyAndBoundsTheWholeRemoteCall() {
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().contentLength(12L).contentType("audio/wav").build());
        assertEquals(new S3StorageUtil.UploadMetadata(12, "audio/wav"), storage.inspectUpload("uploads/audio/9/a", timeout));
        verify(client).headObject(argThat((HeadObjectRequest r) -> r.key().equals("uploads/audio/9/a")
                && r.overrideConfiguration().orElseThrow().apiCallTimeout().orElseThrow().equals(timeout)));
    }

    @Test void onlyMissingObjectsAreTreatedAsNotUploaded() {
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(404).build());
        assertNull(storage.inspectUpload("uploads/audio/9/a", timeout));
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(503).build());
        assertThrows(S3Exception.class, () -> storage.inspectUpload("uploads/audio/9/a", timeout));
    }

    @Test void cleanupIsBoundedAndDoesNotSwallowFailures() {
        doThrow(S3Exception.builder().statusCode(503).build()).when(client).deleteObject(any(DeleteObjectRequest.class));
        assertThrows(S3Exception.class, () -> storage.deleteUpload("uploads/audio/9/a", timeout));
        verify(client).deleteObject(argThat((DeleteObjectRequest r) -> r.key().equals("uploads/audio/9/a")
                && r.overrideConfiguration().orElseThrow().apiCallTimeout().orElseThrow().equals(timeout)));
    }
}
