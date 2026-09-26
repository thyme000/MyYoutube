package cn.diaojiahong.myyoutube.search.port;

import cn.diaojiahong.myyoutube.recommend.domain.doc.VideoDocRepository;
import cn.diaojiahong.myyoutube.search.domain.doc.UserDoc;
import cn.diaojiahong.myyoutube.search.domain.doc.UserDocRepository;
import cn.diaojiahong.myyoutube.recommend.domain.doc.VideoDoc;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.port.UserSearchPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.core.suggest.Completion;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserSearchPortImpl implements UserSearchPort {

    private final VideoDocRepository videoDocRepository;
    private final UserDocRepository userDocRepository;

    @Override
    public void onUserProfileChanged(User user) {
        Long id = user.getId();
        try {
            // 更新视频文档的作者昵称
            List<VideoDoc> videoDoc = videoDocRepository.findVideoDocByAuthorId(id);
            if (videoDoc != null && !videoDoc.isEmpty()) {
                videoDoc.forEach(v -> {
                    v.setAuthorName(user.getNickname());
                    v.setSuggest(new Completion(List.of(
                            v.getTitle(),
                            v.getDescription(),
                            user.getNickname(),
                            String.join(" ", v.getTagNames())
                    )));
                });
                videoDocRepository.saveAll(videoDoc);
            }
        } catch (Exception e) {
            log.error("更新视频文档的作者昵称失败...", e);
        }
        try {
            // 修改用户文档的用户昵称和简介
            UserDoc userDoc = userDocRepository.findById(id).orElse(null);
            if (userDoc != null) {
                userDoc.setNickname(user.getNickname());
                userDoc.setIntroduction(user.getIntroduction());
                userDoc.setSuggest(new Completion(List.of(
                        user.getNickname()
                )));
                userDocRepository.save(userDoc);
            } else {
                // 新增用户文档
                userDoc = new UserDoc();
                userDoc.setId(id);
                userDoc.setUsername(user.getUsername());
                userDoc.setNickname(user.getNickname());
                userDoc.setIntroduction(user.getIntroduction());
                userDoc.setLikeCount(user.getLikeCount());
                userDoc.setVideoCount(user.getVideoCount());
                userDoc.setCreateTime(user.getCreateTime());
                userDoc.setFans(user.getFans());
                userDoc.setSuggest(new Completion(List.of(
                        user.getIntroduction(),
                        user.getNickname()
                )));
                userDocRepository.save(userDoc);
            }
        } catch (Exception e) {
            log.error("更新用户文档的用户昵称和简介失败...", e);
        }
    }
}
