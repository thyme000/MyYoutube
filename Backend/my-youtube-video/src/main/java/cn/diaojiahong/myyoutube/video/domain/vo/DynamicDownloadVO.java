package cn.diaojiahong.myyoutube.video.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DynamicDownloadVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private String taskId;

    /** PENDING/PROCESSING/DONE/FAILED */
    private String status;

    /** DONE 时才有的成片签名下载地址 */
    private String downloadUrl;
}
