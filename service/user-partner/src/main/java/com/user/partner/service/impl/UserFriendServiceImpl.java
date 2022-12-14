package com.user.partner.service.impl;


import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.user.model.constant.RedisKey;
import com.user.model.domain.ChatRecord;
import com.user.model.domain.User;
import com.user.model.domain.UserFriend;
import com.user.model.resp.FriendUserResponse;
import com.user.openfeign.UserOpenFeign;
import com.user.partner.mapper.UserFriendMapper;
import com.user.partner.service.IChatRecordService;
import com.user.partner.service.IUserFriendService;
import com.user.rabbitmq.config.mq.MqClient;
import com.user.rabbitmq.config.mq.RabbitService;
import com.user.util.common.ErrorCode;
import com.user.util.exception.GlobalException;
import com.user.util.utils.UserUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author ice
 * @since 2022-07-28
 */
@Service
public class UserFriendServiceImpl extends ServiceImpl<UserFriendMapper, UserFriend> implements IUserFriendService {
    @Resource
    private UserOpenFeign userOpenFeign;
    @Resource
    private IChatRecordService chatRecordService;
    @Resource
    private RabbitService rabbitService;

    @Override
    @Transactional
    public void addFriendReq(String reqId,String userId) {

        UserFriend userFriend = new UserFriend();
        userFriend.setUserId(userId);
        userFriend.setFriendsId(reqId);
        int insert = baseMapper.insert(userFriend);
        if (insert <= 0) {
            throw new RuntimeException("添加好友失败");
        }
        String redisKey = RedisKey.selectFriend + userId;
        rabbitService.sendMessage(MqClient.DIRECT_EXCHANGE,MqClient.REMOVE_REDIS_KEY,redisKey);
    }

    @Override
    public List<User> selectFriend(String userId) {
        QueryWrapper<UserFriend> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId).or().eq("friends_id", userId);
        List<UserFriend> userFriends = baseMapper.selectList(wrapper);
        if (CollectionUtils.isEmpty(userFriends)) {
            return null;
        }
        List<String> userIdByList = new ArrayList<>();
        userFriends.forEach(userFriend -> {
            if (userFriend.getUserId().equals(userId)) {
                userIdByList.add(userFriend.getFriendsId());
            }
            if (userFriend.getFriendsId().equals(userId)) {
                userIdByList.add(userFriend.getUserId());
            }
        });
        if (!CollectionUtils.isEmpty(userIdByList)) {
            return userOpenFeign.getListByIds(userIdByList);
        }

        return null;
    }

    @Override
    public FriendUserResponse getFriendUser(String friendId, HttpServletRequest request) {
        User loginUser = UserUtils.getLoginUser(request);
        if (!StringUtils.hasText(friendId)) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "参数错误...");
        }
        String userId = loginUser.getId();
        UserFriend userFriend = this.getUserFriendByFriendId(friendId, userId);
        if (userFriend == null) {
            throw new GlobalException(ErrorCode.SYSTEM_EXCEPTION);
        }
        LocalDateTime createTime = userFriend.getCreateTime();
        Date date = Date.from( createTime.atZone( ZoneId.systemDefault()).toInstant());
        User feignUser = userOpenFeign.getUserById(friendId);
        if (feignUser == null) {
            throw new GlobalException(ErrorCode.SYSTEM_EXCEPTION);
        }
        FriendUserResponse response = new FriendUserResponse();
        BeanUtils.copyProperties(feignUser,response);
        response.setCreateTime(date);
        return response;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean delFriendUser(String friendId, String userId) {

        if (!StringUtils.hasText(friendId)) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "参数错误...");
        }

        UserFriend userFriend = this.getUserFriendByFriendId(friendId, userId);
        if (userFriend == null) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "参数错误...");
        }
        boolean removeById = this.removeById(userFriend);
        if (!removeById) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "参数错误...");
        }
        QueryWrapper<ChatRecord> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId).and(q -> q.eq("friend_id", friendId)).
                or().eq("user_id", friendId).and(q -> q.eq("friend_id", userId));
        long count = chatRecordService.count(wrapper);
        if (count > 0) {
            return chatRecordService.remove(wrapper);
        }
        String redisKey = RedisKey.selectFriend + userId;
        rabbitService.sendMessage(MqClient.DIRECT_EXCHANGE,MqClient.REMOVE_REDIS_KEY,redisKey);
        return true;
    }

    private UserFriend  getUserFriendByFriendId(String friendId,String userId) {
        QueryWrapper<UserFriend> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId).and(q -> q.eq("friends_id", friendId)).
                or().eq("user_id", friendId).and(q -> q.eq("friends_id", userId));
        return this.getOne(wrapper);
    }
}
