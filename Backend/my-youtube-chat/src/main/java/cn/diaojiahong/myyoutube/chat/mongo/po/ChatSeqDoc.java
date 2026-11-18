package cn.diaojiahong.myyoutube.chat.mongo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("chat_conversation_seq")
public class ChatSeqDoc {
    @TableId(value = "conversation_id", type = IdType.INPUT)
    private String id;
    private Long seq;
}
