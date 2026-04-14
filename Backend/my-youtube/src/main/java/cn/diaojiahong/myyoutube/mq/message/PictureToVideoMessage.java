package cn.diaojiahong.myyoutube.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 多图 + 可选 OSS 音频 → 幻灯片视频（MQ 可序列化，不含 MultipartFile）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PictureToVideoMessage {

    private String jobId;
    private Long userId;
    private List<String> objectKeys;
    /** OSS objectKey，可为空（成片无音轨，仅画面） */
    private String audioObjectKey;
}
