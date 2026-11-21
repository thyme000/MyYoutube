package cn.diaojiahong.myyoutube.user.service;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.user.domain.dto.UserDTO;
import cn.diaojiahong.myyoutube.user.domain.dto.UserHistoryPageQuery;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.domain.vo.CurrentUserVO;
import cn.diaojiahong.myyoutube.user.domain.vo.UserVO;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

public interface IUserService extends IService<User> {

    UserVO getUserInfo(Long userId);

    String updateAvatar(MultipartFile avatar);

    String updateBgImage(MultipartFile bgImage);

    String updateCurrentUser(UserDTO userDTO);

    Map<Long, String> getAuthorNicknames(List<Long> ids);

    Map<Long, String> getAuthorAvatarUrls(List<Long> authorIds);

    CurrentUserVO getCurrentUserInfo();

}
