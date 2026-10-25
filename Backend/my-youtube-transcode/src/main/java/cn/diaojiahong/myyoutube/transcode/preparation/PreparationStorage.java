package cn.diaojiahong.myyoutube.transcode.preparation;

import cn.diaojiahong.myyoutube.common.properties.S3Properties;
import cn.diaojiahong.myyoutube.common.media.InvalidMediaInputException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
@RequiredArgsConstructor
public class PreparationStorage {
    private final S3Client s3;
    private final S3Properties properties;

    public record Source(String key, String versionId, String etag, long bytes) {}

    public Source inspect(String key) {
        var head = s3.headObject(HeadObjectRequest.builder().bucket(properties.getBucketName()).key(key).build());
        if (head.contentLength() == null || head.eTag() == null || head.eTag().isBlank())
            throw new IllegalStateException("存储对象缺少条件校验元数据");
        if (head.contentLength() <= 0) throw new InvalidMediaInputException(InvalidMediaInputException.Reason.EMPTY_MEDIA);
        return new Source(key, head.versionId(), head.eTag(), head.contentLength());
    }

    public Source inspectIfPresent(String key) {
        try { return inspect(key); }
        catch (S3Exception e) {
            if (e.statusCode() == 404) return null;
            throw e;
        }
    }

    public Source freeze(Source source, String destination) {
        protectedKey(destination);
        String copySource = encode(properties.getBucketName() + "/" + source.key());
        if (source.versionId() != null && !source.versionId().equals("null"))
            copySource += "?versionId=" + encode(source.versionId());
        s3.copyObject(CopyObjectRequest.builder().bucket(properties.getBucketName()).key(destination)
                .copySource(copySource).copySourceIfMatch(source.etag()).ifNoneMatch("*").build());
        var frozen = inspect(destination);
        if (frozen.bytes() != source.bytes()) throw new IllegalStateException("原件复制长度不一致");
        return frozen;
    }

    public void download(Source source, Path destination, long maxBytes) throws Exception {
        if (source.bytes() > maxBytes) throw new InvalidMediaInputException(InvalidMediaInputException.Reason.MEDIA_TOO_LARGE);
        var request = GetObjectRequest.builder().bucket(properties.getBucketName()).key(source.key()).ifMatch(source.etag());
        if (source.versionId() != null && !source.versionId().equals("null")) request.versionId(source.versionId());
        boolean completed = false;
        try (var input = s3.getObject(request.build()); var output = Files.newOutputStream(destination)) {
            long size = 0;
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                size += read;
                if (size > maxBytes) throw new InvalidMediaInputException(InvalidMediaInputException.Reason.MEDIA_TOO_LARGE);
                if (size > source.bytes()) throw new IllegalStateException("素材读取大小与存储元数据不一致");
                output.write(buffer, 0, read);
            }
            if (size != source.bytes()) throw new IllegalStateException("素材读取不完整");
            completed = true;
        } finally {
            if (!completed) try { Files.deleteIfExists(destination); }
            catch (java.io.IOException error) { log.warn("媒体准备下载临时文件清理失败", error); }
        }
    }

    public void upload(Path source, String key) {
        protectedKey(key);
        s3.putObject(PutObjectRequest.builder().bucket(properties.getBucketName()).key(key)
                .contentType("image/jpeg").ifNoneMatch("*").build(), RequestBody.fromFile(source));
    }

    public void delete(String key) {
        protectedKey(key);
        var source = inspectIfPresent(key);
        if (source == null) return;
        var request = DeleteObjectRequest.builder().bucket(properties.getBucketName()).key(key);
        if (source.versionId() != null && !"null".equals(source.versionId())) request.versionId(source.versionId());
        s3.deleteObject(request.build());
    }

    private static String encode(String key) {
        return URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20").replace("%2F", "/");
    }

    private static void protectedKey(String key) {
        if (key == null || !key.startsWith("media-prepared/") || key.contains("..") || key.contains("\\"))
            throw new IllegalArgumentException("非法媒体准备对象键");
    }
}
