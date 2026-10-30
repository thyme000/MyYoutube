package cn.diaojiahong.myyoutube.common.utils;

import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.properties.S3Properties;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3FailureTest {
    final S3Client client = mock(S3Client.class);
    final S3StorageUtil storage;

    S3FailureTest() {
        var properties = new S3Properties();
        properties.setBucketName("test");
        storage = new S3StorageUtil(properties, client, mock(S3Presigner.class));
    }

    @Test
    void storageFailuresKeepTheirCauseWithoutExposingUpstreamDetails() {
        for (int status : new int[]{403, 429, 503, 504}) {
            AwsServiceException cause = S3Exception.builder()
                    .statusCode(status)
                    .message("private upstream endpoint")
                    .build();
            when(client.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow(cause);
            DependencyException error = assertThrows(
                    DependencyException.class,
                    () -> storage.readHeadBytes("private-key", 10));
            assertSame(cause, error.getCause());
            assertFalse(error.getMessage().contains("private"));
            assertEquals(status == 504 ? ErrorCode.DEPENDENCY_TIMEOUT
                    : status == 403 ? ErrorCode.DEPENDENCY_FAILURE : ErrorCode.DEPENDENCY_UNAVAILABLE,
                    error.getErrorCode());
        }
    }

    @Test
    void missingStoredArtifactIsAnExplicitDependencyFailure() {
        NoSuchKeyException cause = NoSuchKeyException.builder().message("private-key").build();
        when(client.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow(cause);
        DependencyException error = assertThrows(
                DependencyException.class,
                () -> storage.readHeadBytes("private-key", 10));
        assertSame(cause, error.getCause());
        assertEquals(ErrorCode.DEPENDENCY_FAILURE, error.getErrorCode());
        assertEquals("所需存储对象不存在", error.getMessage());
    }
}
