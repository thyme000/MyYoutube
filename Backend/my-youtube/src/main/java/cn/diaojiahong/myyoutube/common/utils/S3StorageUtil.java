package cn.diaojiahong.myyoutube.common.utils;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.properties.S3Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * S3 兼容对象存储工具（RustFS/MinIO 通用），配置来自 {@link S3Properties}（my-youtube.s3）
 */
@Slf4j
@Component
public class S3StorageUtil {

    private static final DateTimeFormatter DATE_FOLDER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final S3Properties properties;
    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    public S3StorageUtil(S3Properties properties,
                         @Autowired S3Client s3Client,
                         @Autowired S3Presigner s3Presigner) {
        this.properties = properties;
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
    }

    /**
     * 上传文件：自动生成唯一对象键，避免同用户同名文件覆盖。
     *
     * @param bytes           文件字节
     * @param originalFilename 原始文件名（仅用于展示后缀与可读名，会净化）
     * @param userId          用户 ID，用于区分目录；可为 null
     * @param bizPrefix       业务目录前缀，如 avatar、bg、video
     */
    public String upload(byte[] bytes, String originalFilename, Long userId, String bizPrefix) {
        String objectKey = buildUniqueObjectKey(userId, bizPrefix, originalFilename);
        putObject(bytes, objectKey);
        return buildObjectUrl(objectKey);
    }

    /**
     * 上传文件并返回 objectKey。
     */
    public String uploadForObjectKey(byte[] bytes, String originalFilename, Long userId, String bizPrefix) {
        String objectKey = buildUniqueObjectKey(userId, bizPrefix, originalFilename);
        putObject(bytes, objectKey);
        return objectKey;
    }

    /**
     * 上传文件（兼容）：仍会自动生成唯一键，目录为 upload/anonymous。
     */
    public String upload(byte[] bytes, String originalFilename) {
        return upload(bytes, originalFilename, null, "upload");
    }

    /**
     * 生成对象键：uploads/{biz}/{userId|anonymous}/{yyyyMMdd}/{uuid}_{safeName}
     */
    public String buildUniqueObjectKey(Long userId, String bizPrefix, String originalFilename) {
        String biz = StringUtils.hasText(bizPrefix) ? sanitizePathSegment(bizPrefix) : "upload";
        String uid = userId != null ? String.valueOf(userId) : "anonymous";
        String dateFolder = LocalDate.now().format(DATE_FOLDER);
        String unique = UUID.randomUUID().toString().replace("-", "");
        String safeName = sanitizeFilename(originalFilename);
        return String.format("uploads/%s/%s/%s/%s_%s", biz, uid, dateFolder, unique, safeName);
    }

    /** 仅允许字母数字与短横线，避免路径穿越 */
    private static String sanitizePathSegment(String s) {
        if (!StringUtils.hasText(s)) {
            return "upload";
        }
        return s.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    /**
     * 取文件名、去除路径、替换非法字符，限制长度。
     */
    private static String sanitizeFilename(String name) {
        if (!StringUtils.hasText(name)) {
            return "file";
        }
        String base = name.trim();
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (base.isEmpty()) {
            base = "file";
        }
        if (base.length() > 120) {
            int dot = base.lastIndexOf('.');
            if (dot > 0 && dot < base.length() - 1) {
                String ext = base.substring(dot);
                base = base.substring(0, Math.min(100, dot)) + ext;
            } else {
                base = base.substring(0, 120);
            }
        }
        return base;
    }

    /**
     * 将输入统一为 objectKey：
     * 已是 key：原样返回
     * 是 http/https URL：提取 path 作为 key
     */
    public String normalizeToObjectKey(String keyOrUrl) {
        if (!StringUtils.hasText(keyOrUrl)) {
            return keyOrUrl;
        }
        String value = keyOrUrl.trim();
        if (!(value.startsWith("http://") || value.startsWith("https://"))) {
            return value;
        }
        try {
            URI uri = URI.create(value);
            String path = uri.getPath();
            if (!StringUtils.hasText(path)) {
                return value;
            }
            String key = path.startsWith("/") ? path.substring(1) : path;
            // 兼容路径风格 URL 中的 /{bucket}/ 前缀
            String bucket = properties.getBucketName();
            if (bucket != null && key.startsWith(bucket + "/")) {
                key = key.substring(bucket.length() + 1);
            }
            return key;
        } catch (Exception e) {
            log.warn("URL 转 objectKey 失败，原样返回: {}", value);
            return value;
        }
    }

    /**
     * 生成私有读 GET 预签名 URL。
     * 支持传入 objectKey 或 URL（会自动提取 key）。
     */
    public String signGetUrl(String keyOrUrl, long expireSeconds) {
        if (!StringUtils.hasText(keyOrUrl)) {
            return keyOrUrl;
        }
        String objectKey = normalizeToObjectKey(keyOrUrl);
        if (!StringUtils.hasText(objectKey)) {
            return keyOrUrl;
        }
        try {
            GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofSeconds(Math.max(1, expireSeconds)))
                    .getObjectRequest(b -> b.bucket(properties.getBucketName()).key(objectKey))
                    .build();
            return s3Presigner.presignGetObject(request).url().toString();
        } catch (Exception e) {
            log.warn("生成 GET 预签名失败，降级返回原值: {}", objectKey, e);
            return keyOrUrl;
        }
    }

    /**
     * 生成前端直传用 PUT 预签名 URL（仅授权写入指定 objectKey，带过期时间）。
     */
    public String presignPutUrl(String objectKey, long expireSeconds) {
        PutObjectPresignRequest request = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(Math.max(1, expireSeconds)))
                .putObjectRequest(b -> b.bucket(properties.getBucketName()).key(objectKey))
                .build();
        return s3Presigner.presignPutObject(request).url().toString();
    }

    private void putObject(byte[] bytes, String objectKey) {
        try {
            s3Client.putObject(
                    PutObjectRequest.builder().bucket(properties.getBucketName()).key(objectKey).build(),
                    RequestBody.fromBytes(bytes));
            log.info("文件上传到 objectKey: {}", objectKey);
        } catch (S3Exception e) {
            log.warn("S3 上传失败 objectKey={} code={}", objectKey, e.statusCode());
            throw new BizException(BizException.OSS_ERROR, "对象存储上传失败: " + e.getMessage());
        }
    }

    /**
     * 删除对象（支持传入 URL，会先规范为 objectKey）。S3 删除天然幂等，对象不存在不算失败。
     * 失败仅打日志，不抛异常，便于合成后清理素材等场景。
     */
    public void deleteObjectQuietly(String keyOrUrl) {
        if (!StringUtils.hasText(keyOrUrl)) {
            return;
        }
        String objectKey = normalizeToObjectKey(keyOrUrl.trim());
        if (!StringUtils.hasText(objectKey)) {
            return;
        }
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.getBucketName()).key(objectKey).build());
            log.debug("对象删除成功: {}", objectKey);
        } catch (Exception e) {
            log.warn("对象删除失败 objectKey={} msg={}", objectKey, e.getMessage());
        }
    }

    /** 路径风格公开 URL：{endpoint}/{bucket}/{key} */
    private String buildObjectUrl(String objectKey) {
        String endpoint = properties.getEndpoint();
        return endpoint.replaceAll("/+$", "") + "/" + properties.getBucketName() + "/" + objectKey;
    }
}
