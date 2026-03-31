package cn.diaojiahong.myyoutube.user.service;

import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.user.domain.dto.UserDTO;
import cn.diaojiahong.myyoutube.user.domain.dto.UserHistoryPageQuery;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.domain.vo.UserLongHistoryVideoVO;
import cn.diaojiahong.myyoutube.user.domain.vo.UserVO;
import cn.diaojiahong.myyoutube.video.domain.vo.ShortsVO;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 用户核心表（视频发布者/网站登录用户） 服务类
 * </p>
 *
 * @author thyme
 * @since 2026-03-18
 */
public interface IUserService extends IService<User> {

    Result<UserVO> getUserInfo();

    Result<String> updateAvatar(MultipartFile avatar);

    Result<String> updateBgImage(MultipartFile bgImage);

    Result<String> updateUser(Long id, UserDTO userDTO);

    Map<Long, String> getAuthorNicknames(List<Long> ids);

    Map<Long, String> getAuthorAvatarUrls(List<Long> authorIds);

    Result<PageResult<UserLongHistoryVideoVO>> getLongHistory(UserHistoryPageQuery userHistoryPageQuery);

    Result<PageResult<ShortsVO>> getShortsHistory(UserHistoryPageQuery userHistoryPageQuery);

}
