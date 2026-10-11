package cn.diaojiahong.myyoutube.video.domain.enums;
import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
@Getter
public enum ContentReviewState {
    WAITING_PIPELINE, AUTO_PENDING, AUTO_RUNNING, MANUAL_PENDING, APPROVED, REJECTED, CANCELLED;
    @EnumValue private final String code = name();
}
