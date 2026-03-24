package cn.diaojiahong.myyoutube.common.utils;

import cn.diaojiahong.myyoutube.common.properties.AliOssProperties;
import com.aliyun.oss.*;
import com.aliyun.oss.common.auth.CredentialsProviderFactory;
import com.aliyun.oss.common.auth.EnvironmentVariableCredentialsProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Date;
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

    /**
     * 生成put预签名(上传)
     * @param objectName Object完整路径中不能包含Bucket名称。
     */
    public static String GetPutSignUrl(String objectName) throws Throwable {
        // 以华东1（杭州）的外网Endpoint为例，其它Region请按实际情况填写。
        String endpoint = "https://oss-cn-hangzhou.aliyuncs.com";
        // 从环境变量中获取访问凭证。运行本代码示例之前，请确保已设置环境变量OSS_ACCESS_KEY_ID和OSS_ACCESS_KEY_SECRET。
        EnvironmentVariableCredentialsProvider credentialsProvider = CredentialsProviderFactory.newEnvironmentVariableCredentialsProvider();
        // 填写Bucket名称，例如examplebucket。
        String bucketName = "thyme";
        // 填写Bucket所在地域。以华东1（杭州）为例，Region填写为cn-hangzhou。
        String region = "ap-southeast-1";

        // 创建OSSClient实例。
        // 当OSSClient实例不再使用时，调用shutdown方法以释放资源。
        ClientBuilderConfiguration clientBuilderConfiguration = new ClientBuilderConfiguration();
        clientBuilderConfiguration.setSignatureVersion(SignVersion.V4);
        OSS ossClient = OSSClientBuilder.create()
                .endpoint(endpoint)
                .credentialsProvider(credentialsProvider)
                .clientConfiguration(clientBuilderConfiguration)
                .region(region)
                .build();

        URL signedUrl = null;
        try {
            // 指定生成的预签名URL过期时间，单位为毫秒。
            Date expiration = new Date(System.currentTimeMillis() + 3600 * 1000L); // 两天

            // 生成预签名URL。
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucketName, objectName, HttpMethod.PUT);
            // 设置过期时间。
            request.setExpiration(expiration);
            // 通过HTTP PUT请求生成预签名URL。
            signedUrl = ossClient.generatePresignedUrl(request);
            // 打印预签名URL。
            System.out.println("signed url for putObject: " + signedUrl);

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
        }

        return signedUrl == null ? null : signedUrl.toString();
    }

    /**
     * 生成get签名(下载)
     * @param objectName Object完整路径中不能包含Bucket名称。
     * @throws Throwable
     */
    public static String getSignUrlForGetObject(String objectName) throws Throwable {
        // 以华东1（杭州）的外网Endpoint为例，其它Region请按实际情况填写。
        String endpoint = "https://oss-cn-hangzhou.aliyuncs.com";
        // 从环境变量中获取访问凭证。运行本代码示例之前，请确保已设置环境变量OSS_ACCESS_KEY_ID和OSS_ACCESS_KEY_SECRET。
        EnvironmentVariableCredentialsProvider credentialsProvider = CredentialsProviderFactory.newEnvironmentVariableCredentialsProvider();
        // 填写Bucket名称，例如examplebucket。
        String bucketName = "thyme";
        // 填写Bucket所在地域。以华东1（杭州）为例，Region填写为cn-hangzhou。
        String region = "ap-southeast-1";

        // 创建OSSClient实例。
        // 当OSSClient实例不再使用时，调用shutdown方法以释放资源。
        ClientBuilderConfiguration clientBuilderConfiguration = new ClientBuilderConfiguration();
        clientBuilderConfiguration.setSignatureVersion(SignVersion.V4);
        OSS ossClient = OSSClientBuilder.create()
                .endpoint(endpoint)
                .credentialsProvider(credentialsProvider)
                .clientConfiguration(clientBuilderConfiguration)
                .region(region)
                .build();
        URL url = null;
        try {
            // 设置预签名URL过期时间，单位为毫秒。本示例以设置过期时间为1小时为例。
            Date expiration = new Date(System.currentTimeMillis() + 3600 * 1000L);
            // 生成以GET方法访问的预签名URL。本示例没有额外请求头，其他人可以直接通过浏览器访问相关内容。
            url = ossClient.generatePresignedUrl(bucketName, objectName, expiration);
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
            if (ossClient != null) {
                ossClient.shutdown();
            }
        }
        return url == null ? null : url.toString();
    }
}
