package cn.diaojiahong.myyoutube.transcode.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("transcode_task")
public class TranscodeTask implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private String taskKey;

    private Long contentId;

    private Long userId;

    private String status;

    private String requestJson;

    private String resultObjectKey;

    private String errorMessage;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private LocalDateTime nextDispatchAt;
}
