package cn.diaojiahong.myyoutube.common.utils;

import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.media.MediaInputs;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.properties.S3Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * S3 兼容对象存储工具（RustFS/MinIO 通用），配置来自 {@link S3Properties}（my-youtube.s3）
 */
@Slf4j
@Component
public class S3StorageUtil {

    private static final DateTimeFormatter DATE_FOLDER = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** SigV4 scope 日期必须纯 8 位, BASIC_ISO_DATE 对 UTC 零偏移会追加 Z */
    private static final DateTimeFormatter AMZ_DATE_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

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
     * 上传到指定 objectKey（同内容幂等覆盖，适合默认资源等固定键场景）。
     */
    public void uploadWithKey(byte[] bytes, String objectKey) {
        putObject(bytes, objectKey);
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

    public String presignPutUrl(String objectKey, long expireSeconds, long contentLength, String contentType) {
        if (contentLength <= 0 || !StringUtils.hasText(contentType))
            throw new IllegalArgumentException("上传大小和类型不能为空");
        var request = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(Math.max(1, expireSeconds)))
                .putObjectRequest(b -> b.bucket(properties.getBucketName()).key(objectKey)
                        .contentLength(contentLength).contentType(contentType))
                .build();
        return s3Presigner.presignPutObject(request).url().toString();
    }

    public record UploadMetadata(long bytes, String contentType) {}

    public UploadMetadata inspectUpload(String key, Duration timeout) {
        requireUploadKey(key);
        try {
            var head = s3Client.headObject(software.amazon.awssdk.services.s3.model.HeadObjectRequest.builder()
                    .bucket(properties.getBucketName()).key(key).overrideConfiguration(c -> c.apiCallTimeout(timeout)).build());
            return new UploadMetadata(head.contentLength(), head.contentType());
        } catch (S3Exception error) {
            if (error.statusCode() == 404) return null;
            throw error;
        }
    }

    public void deleteUpload(String key, Duration timeout) {
        requireUploadKey(key);
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(properties.getBucketName()).key(key)
                .overrideConfiguration(c -> c.apiCallTimeout(timeout)).build());
    }

    private static void requireUploadKey(String key) {
        MediaInputs.objectKey(key);
        if (!key.startsWith("uploads/")) throw new IllegalArgumentException("Expected upload key");
    }

    /**
     * 生成 presigned POST 表单（SDK v2 不支持, 手签 SigV4 POST policy）。
     * 签名时锁定 objectKey/大小上下限/Content-Type 前缀, 存储层直接拒绝越界上传。
     */
    public PresignPostForm presignPostForm(String objectKey, String contentTypePrefix,
                                           long minBytes, long maxBytes, long expireSeconds) {
        String bucket = properties.getBucketName();
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        String date = now.format(AMZ_DATE_DAY);
        String dateTime = now.format(AMZ_DATE);
        String credential = properties.getAccessKey() + "/" + date + "/" + properties.getRegion() + "/s3/aws4_request";
        String expiration = now.plusSeconds(Math.max(1, expireSeconds))
                .format(DateTimeFormatter.ISO_INSTANT);

        String policy;
        try {
            Map<String, Object> policyMap = Map.of(
                    "expiration", expiration,
                    "conditions", List.of(
                            Map.of("bucket", bucket),
                            Map.of("key", objectKey),
                            List.of("content-length-range", minBytes, maxBytes),
                            List.of("starts-with", "$Content-Type", contentTypePrefix),
                            Map.of("x-amz-credential", credential),
                            Map.of("x-amz-algorithm", "AWS4-HMAC-SHA256"),
                            Map.of("x-amz-date", dateTime)
                    ));
            policy = Base64.getEncoder().encodeToString(
                    objectMapperValue(policyMap).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("生成上传策略失败", e);
        }

        byte[] kDate = hmac(("AWS4" + properties.getSecretKey()).getBytes(StandardCharsets.UTF_8),
                date.getBytes(StandardCharsets.UTF_8));
        byte[] kRegion = hmac(kDate, properties.getRegion().getBytes(StandardCharsets.UTF_8));
        byte[] kService = hmac(kRegion, "s3".getBytes(StandardCharsets.UTF_8));
        byte[] kSigning = hmac(kService, "aws4_request".getBytes(StandardCharsets.UTF_8));
        String signature = HexFormat.of().formatHex(hmac(kSigning, policy.getBytes(StandardCharsets.UTF_8)));

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("key", objectKey);
        fields.put("policy", policy);
        fields.put("x-amz-algorithm", "AWS4-HMAC-SHA256");
        fields.put("x-amz-credential", credential);
        fields.put("x-amz-date", dateTime);
        fields.put("x-amz-signature", signature);
        String url = properties.getEndpoint().replaceAll("/+$", "") + "/" + bucket;
        return new PresignPostForm(url, fields, expireSeconds);
    }

    private String objectMapperValue(Map<String, Object> map) throws Exception {
        // JSON 字段序无关紧要, 但要保证无空格差异问题——用 jackson 紧凑输出
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    /** presigned POST 表单: 前端以 multipart/form-data 提交到 url, fields 全部原样携带 */
    public record PresignPostForm(String url, Map<String, String> fields, Long expireSeconds) {
    }

    /** 读对象头部字节(Range 请求), 供 Magic Number 等真身校验, 不拉全量 */
    public byte[] readHeadBytes(String objectKey, int length) {
        try {
            return s3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(properties.getBucketName())
                    .key(objectKey)
                    .range("bytes=0-" + (Math.max(1, length) - 1))
                    .build()).asByteArray();
        } catch (NoSuchKeyException e) {
            throw new DependencyException(ErrorCode.DEPENDENCY_FAILURE, "所需存储对象不存在", e);
        } catch (S3Exception e) {
            throw storageFailure("读取存储对象失败", e);
        }
    }

    public boolean objectExists(String objectKey) {
        try {
            return s3Client.headObject(software.amazon.awssdk.services.s3.model.HeadObjectRequest.builder()
                    .bucket(properties.getBucketName()).key(normalizeToObjectKey(objectKey)).build()).contentLength() > 0;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return false;
            throw e;
        }
    }

    public byte[] readObject(String objectKey) {
        return s3Client.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(properties.getBucketName()).key(normalizeToObjectKey(objectKey)).build()).asByteArray();
    }

    private void putObject(byte[] bytes, String objectKey) {
        try {
            s3Client.putObject(
                    PutObjectRequest.builder().bucket(properties.getBucketName()).key(objectKey).build(),
                    RequestBody.fromBytes(bytes));
            // 逐对象写入, 调用方(帧/小图/产物)已有批次级汇总, 这里只留排查细节
            log.debug("对象写入完成 objectKey={} bytes={}", objectKey, bytes == null ? 0 : bytes.length);
        } catch (S3Exception e) {
            throw storageFailure("对象存储上传失败", e);
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
    private static DependencyException storageFailure(String message, S3Exception cause) {
        ErrorCode code = switch (cause.statusCode()) {
            case 429, 503 -> ErrorCode.DEPENDENCY_UNAVAILABLE;
            case 504 -> ErrorCode.DEPENDENCY_TIMEOUT;
            default -> ErrorCode.DEPENDENCY_FAILURE;
        };
        return new DependencyException(code, message, cause);
    }

}
