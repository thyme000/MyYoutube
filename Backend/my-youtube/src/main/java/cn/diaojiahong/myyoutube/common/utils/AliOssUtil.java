package cn.diaojiahong.myyoutube.common.utils;

import cn.diaojiahong.myyoutube.common.properties.AliOssProperties;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * OSS 上传工具，配置来自 {@link AliOssProperties}（my-youtube.alioss）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AliOssUtil {

    private static final DateTimeFormatter DATE_FOLDER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final AliOssProperties properties;

    /**
     * 上传文件（推荐）：自动生成唯一对象键，避免同用户同名文件覆盖。
     *
     * @param bytes             文件字节
     * @param originalFilename    原始文件名（仅用于展示后缀与可读名，会净化）
     * @param userId            用户 ID，用于区分目录；可为 null
     * @param bizPrefix         业务目录前缀，如 avatar、bg、video
     */
    public String upload(byte[] bytes, String originalFilename, Long userId, String bizPrefix) {
        String objectKey = buildUniqueObjectKey(userId, bizPrefix, originalFilename);
        return putObject(bytes, objectKey);
    }

    /**
     * 上传文件（兼容）：仍会自动生成唯一键，目录为 upload/anonymous。
     */
    public String upload(byte[] bytes, String originalFilename) {
        return upload(bytes, originalFilename, null, "upload");
    }

    /**
     * 生成 OSS 对象键：uploads/{biz}/{userId|anonymous}/{yyyyMMdd}/{uuid}_{safeName}
     */
    private String buildUniqueObjectKey(Long userId, String bizPrefix, String originalFilename) {
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

    private String putObject(byte[] bytes, String objectKey) {
        String endpoint = properties.getEndpoint();
        String accessKeyId = properties.getAccessKeyId();
        String accessKeySecret = properties.getAccessKeySecret();
        String bucketName = properties.getBucketName();

        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);

        try {
            ossClient.putObject(bucketName, objectKey, new ByteArrayInputStream(bytes));
        } catch (OSSException oe) {
            System.out.println("Caught an OSSException, which means your request made it to OSS, "
                    + "but was rejected with an error response for some reason.");
            System.out.println("Error Message:" + oe.getErrorMessage());
            System.out.println("Error Code:" + oe.getErrorCode());
            System.out.println("Request ID:" + oe.getRequestId());
            System.out.println("Host ID:" + oe.getHostId());
        } catch (ClientException ce) {
            System.out.println("Caught an ClientException, which means the client encountered "
                    + "a serious internal problem while trying to communicate with OSS, "
                    + "such as not being able to access the network.");
            System.out.println("Error Message:" + ce.getMessage());
        } finally {
            ossClient.shutdown();
        }

        String url = "https://" + bucketName + "." + endpoint + "/" + objectKey;
        log.info("文件上传到: {}", url);
        return url;
    }
}
