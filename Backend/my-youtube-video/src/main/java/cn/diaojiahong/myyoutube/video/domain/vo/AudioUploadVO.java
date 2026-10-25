package cn.diaojiahong.myyoutube.video.domain.vo;

import java.time.LocalDateTime;

public record AudioUploadVO(String audioId, String processingStatus, LocalDateTime expiresAt, File audio, File cover) {
    public record File(String objectKey, String url, String uploadType, long bytes, String contentType) {}
}
