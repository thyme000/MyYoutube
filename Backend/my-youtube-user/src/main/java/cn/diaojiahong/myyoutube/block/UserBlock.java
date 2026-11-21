package cn.diaojiahong.myyoutube.block;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("user_block")
public class UserBlock {
    private Long blockerId;
    private Long blockedId;
}
