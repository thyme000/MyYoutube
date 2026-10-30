package cn.diaojiahong.myyoutube.video.domain.dto;

import lombok.Data;

@Data
public class AudioUploadDTO {
    private String requestId;
    private String title;
    private File audio;
    private File cover;

    public record File(String filename, long bytes, String contentType) {
    }
}
