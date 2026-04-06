package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.mq.message.UserDocMessage;
import cn.diaojiahong.myyoutube.search.domain.doc.UserDoc;
import cn.diaojiahong.myyoutube.search.domain.doc.UserDocRepository;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.elasticsearch.core.suggest.Completion;
import org.springframework.stereotype.Component;

import java.util.List;

import static cn.diaojiahong.myyoutube.common.constants.RabbitMqConstants.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserDocListener {

    private final UserDocRepository userDocRepository;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = UPDATE_USER_DOCUMENT_QUEUE, durable = "true"),
            exchange = @Exchange(name = UPDATE_USER_DOCUMENT_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = UPDATE_USER_DOCUMENT_ROUTING_KEY
    ))
    public void updateUserDoc(UserDocMessage message) {
        log.info("收到更新用户文档消息: {}", message);

        User user = message.getUser();
        UserDoc userDoc = new UserDoc();
        userDoc.setFans(user.getFans());
        userDoc.setId(user.getId());
        userDoc.setNickname(user.getNickname());
        userDoc.setUsername(user.getUsername());
        userDoc.setLikeCount(user.getLikeCount());
        userDoc.setVideoCount(user.getVideoCount());
        userDoc.setCreateTime(user.getCreateTime());
        userDoc.setSuggest(new Completion(List.of(
                user.getNickname()
        )));
        userDocRepository.save(userDoc);
    }
}
