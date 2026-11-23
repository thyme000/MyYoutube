package cn.diaojiahong.myyoutube.mq.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LikeCommentMessage {
    public static final String LIKE_TAG = "like";
    public static final String CANCEL_LIKE_TAG = "unlike";

    private Long commentId;
    private Long senderId;
}
