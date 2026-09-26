package cn.diaojiahong.myyoutube.video.domain.dto;

import lombok.Data;

import java.util.List;

@Data
public class PictureToVideoDTO {
    /** 已上传对象存储的图片 objectKey 列表（顺序即幻灯片顺序） */
    private List<String> objectKeys;
    /** 已上传对象存储的音频 objectKey；可空，则成片为静音轨 */
    private String audioObjectKey;
}
