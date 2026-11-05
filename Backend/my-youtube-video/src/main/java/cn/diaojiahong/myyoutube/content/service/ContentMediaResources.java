package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceReader;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceReader.FillPolicy;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceRegion;
import cn.diaojiahong.myyoutube.content.cache.WarmupResource;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.domain.media.ContentMediaResource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 缓存未签名的媒体数据；缓存命中不代表用户有访问权限，调用方必须先完成访问检查，
 * 再按本次请求签名，避免绕过内容封禁或共享用户授权地址。
 * 调用方完成访问校验后决定一次 TTL 策略，媒体与文本读取使用同一策略。
 * 仅用于媒体写事务之外的读路径。写事务提交前回源可能缓存旧媒体或“不存在”的结果，
 * 独立读事务无法避免这类陈旧缓存。
 * 当前媒体行写入后不再修改；未来新增媒体替换能力时，必须在提交后失效 CONTENT_MEDIA，
 * 否则旧媒体信息会持续返回，直到缓存过期。
 * 清单和字幕按版本化 objectKey 缓存；内容变更必须使用新 key，
 * 覆盖原 key 会导致缓存继续返回旧文本。
 */
@Service
@RequiredArgsConstructor
public class ContentMediaResources {
    private static final Duration TEXT_READ_TIMEOUT = Duration.ofSeconds(10);

    private final ContentMediaResourceLoader loader;
    private final S3StorageUtil storage;

    public WarmupResource<ContentMediaResource> warmupResource(Content content, FillPolicy policy) {
        return new WarmupResource<>(ContentResourceRegion.CONTENT_MEDIA, content.getId().toString(),
                () -> loader.load(content), policy);
    }

    WarmupResource<String> warmupPlaylist(String objectKey, FillPolicy policy) {
        return new WarmupResource<>(ContentResourceRegion.PLAYLIST, objectKey, () -> readText(objectKey), policy);
    }

    WarmupResource<String> warmupSubtitle(String objectKey, FillPolicy policy) {
        return new WarmupResource<>(ContentResourceRegion.SUBTITLE, objectKey, () -> readText(objectKey), policy);
    }

    public ContentMediaResource media(Content content, ContentResourceReader reader, FillPolicy policy) {
        ContentMediaResource resource = reader.get(ContentResourceRegion.CONTENT_MEDIA, content.getId().toString(),
                ContentMediaResource.class, () -> loader.load(content), policy);
        if (resource == null) throw new BizException(ErrorCode.NOT_FOUND, "媒体不存在");
        return resource;
    }

    public String playlist(String objectKey, ContentResourceReader reader, FillPolicy policy) {
        return text(ContentResourceRegion.PLAYLIST, objectKey, reader, policy);
    }

    public String subtitle(String objectKey, ContentResourceReader reader, FillPolicy policy) {
        return text(ContentResourceRegion.SUBTITLE, objectKey, reader, policy);
    }

    private String text(ContentResourceRegion region, String objectKey, ContentResourceReader reader, FillPolicy policy) {
        return reader.get(region, objectKey, String.class, () -> readText(objectKey), policy);
    }

    private String readText(String objectKey) {
        try {
            return new String(storage.readObject(objectKey, TEXT_READ_TIMEOUT), StandardCharsets.UTF_8);
        } catch (SdkException failure) {
            ErrorCode code = ErrorCode.DEPENDENCY_FAILURE;
            if (failure instanceof ApiCallTimeoutException || failure instanceof ApiCallAttemptTimeoutException) {
                code = ErrorCode.DEPENDENCY_TIMEOUT;
            } else if (failure instanceof S3Exception serviceFailure) {
                code = switch (serviceFailure.statusCode()) {
                    case 429, 503 -> ErrorCode.DEPENDENCY_UNAVAILABLE;
                    case 504 -> ErrorCode.DEPENDENCY_TIMEOUT;
                    default -> ErrorCode.DEPENDENCY_FAILURE;
                };
            }
            throw new DependencyException(code, "读取内容资源失败", failure);
        }
    }
}
