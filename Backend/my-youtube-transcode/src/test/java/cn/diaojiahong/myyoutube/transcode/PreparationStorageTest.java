package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.properties.S3Properties;
import cn.diaojiahong.myyoutube.transcode.preparation.PreparationStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreparationStorageTest {
    @TempDir Path work;

    final S3Client client = mock(S3Client.class);

    final S3Properties properties = new S3Properties();

    PreparationStorage storage() {
        properties.setBucketName("media");
        return new PreparationStorage(client,properties);
    }

    @Test
    void freezesWithSourceVersionAndConditionWithoutDownloading() {
        var source = new PreparationStorage.Source("uploads/picture/9/a b.png","v+1","etag",42);
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(42L).eTag("copied").build());
        storage().freeze(source,"media-prepared/9/immutable");
        ArgumentCaptor<CopyObjectRequest> request = org.mockito.ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(client).copyObject(request.capture());
        assertEquals("media/uploads/picture/9/a%20b.png?versionId=v%2B1",request.getValue().copySource());
        assertEquals("etag",request.getValue().copySourceIfMatch());
        assertEquals("*",request.getValue().ifNoneMatch());
        verify(client,never()).getObject(any(GetObjectRequest.class));
    }

    @Test
    void rejectsOversizedResponseAndRemovesPartialFile() throws Exception {
        var source = new PreparationStorage.Source("media-prepared/9/image",null,"etag",5);
        when(client.getObject(any(GetObjectRequest.class)))
                .thenReturn(new ResponseInputStream<>(GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(new byte[6]))));
        Path path = work.resolve("download");
        assertThrows(IllegalArgumentException.class,()->storage().download(source,path,5));
        assertFalse(Files.exists(path));
        ArgumentCaptor<GetObjectRequest> request = org.mockito.ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(client).getObject(request.capture());
        assertEquals("etag",request.getValue().ifMatch());
    }

    @Test
    void changedSourceFailsRatherThanCopyingNewBytes() {
        when(client.copyObject(any(CopyObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(412).message("changed").build());
        assertThrows(
                S3Exception.class,
                ()->storage().freeze(new PreparationStorage.Source("source",null,"old",42),"media-prepared/9/frozen"));
        verify(client,never()).headObject(any(HeadObjectRequest.class));
    }

    @Test
    void optionalInspectionOnlyTreatsNotFoundAsMissing() {
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(404).build());
        assertNull(storage().inspectIfPresent("media-prepared/9/missing"));
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(503).build());
        assertThrows(S3Exception.class, () -> storage().inspectIfPresent("media-prepared/9/unavailable"));
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(403).build());
        assertThrows(S3Exception.class, () -> storage().inspectIfPresent("media-prepared/9/forbidden"));
    }

    @Test
    void deletionUsesExactVersionAndPropagatesFailures() {
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength(42L).eTag("etag").versionId("frozen-version").build());
        storage().delete("media-prepared/9/101/image");
        ArgumentCaptor<DeleteObjectRequest> request = org.mockito.ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(client).deleteObject(request.capture());
        assertEquals("frozen-version", request.getValue().versionId());
        assertThrows(IllegalArgumentException.class, () -> storage().delete("uploads/picture/9/image"));
        when(client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(503).build());
        assertThrows(S3Exception.class, () -> storage().delete("media-prepared/9/101/image"));
    }

    @Test
    void absentObjectDeletionIsIdempotent() {
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(404).build());
        storage().delete("media-prepared/9/101/image");
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

}
