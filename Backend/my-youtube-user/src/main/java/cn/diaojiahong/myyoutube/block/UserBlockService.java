package cn.diaojiahong.myyoutube.block;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.mapper.UserBlockMapper;
import cn.diaojiahong.myyoutube.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserBlockService {
    private final UserBlockMapper blocks;
    private final UserMapper users;

    public void block(Long blockerId, Long blockedId) {
        if (blockedId <= 0 || blockedId.equals(blockerId)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "无法拉黑该用户");
        }
        User target = users.selectById(blockedId);
        if (target == null || !Integer.valueOf(0).equals(target.getIsDelete())) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        UserBlock relationship = new UserBlock();
        relationship.setBlockerId(blockerId);
        relationship.setBlockedId(blockedId);
        try {
            blocks.insert(relationship);
        } catch (DuplicateKeyException alreadyBlocked) {
            // PUT 重试时，该关系已存在。
        }
    }

    public void unblock(Long blockerId, Long blockedId) {
        blocks.delete(pair(blockerId, blockedId));
    }

    public boolean isBlocked(Long blockerId, Long blockedId) {
        return blocks.exists(pair(blockerId, blockedId));
    }

    public boolean blocksPrivateChat(Long userId, Long peerId) {
        return blocks.exists(pair(userId, peerId).or(other -> other
                .eq(UserBlock::getBlockerId, peerId)
                .eq(UserBlock::getBlockedId, userId)));
    }

    public List<Long> blockedAuthors(Long viewerId) {
        return blocks.selectList(new LambdaQueryWrapper<UserBlock>()
                        .select(UserBlock::getBlockedId)
                        .eq(UserBlock::getBlockerId, viewerId))
                .stream().map(UserBlock::getBlockedId).toList();
    }

    private static LambdaQueryWrapper<UserBlock> pair(Long blockerId, Long blockedId) {
        return new LambdaQueryWrapper<UserBlock>()
                .eq(UserBlock::getBlockerId, blockerId)
                .eq(UserBlock::getBlockedId, blockedId);
    }
}
