package com.user.partner.service.impl;


import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.user.model.domain.ChatRecord;
import com.user.model.domain.User;
import com.user.partner.mapper.ChatRecordMapper;
import com.user.partner.service.IChatRecordService;
import com.user.util.utils.UserUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * <p>
 * 聊天记录表 服务实现类
 * </p>
 *
 * @author ice
 * @since 2022-07-28
 */
@Service
public class ChatRecordServiceImpl extends ServiceImpl<ChatRecordMapper, ChatRecord> implements IChatRecordService {
    @Override
    @Transactional
    public List<ChatRecord> selectAllList(String userId, String friendId, HttpServletRequest request) {
        User loginUser = UserUtils.getLoginUser(request);
        QueryWrapper<ChatRecord> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId).and(q -> {
            q.eq("friend_id", friendId);
        }).or().eq("user_id", friendId).and(q -> {
            q.eq("friend_id", userId);
        });
        ChatRecord chatRecord = new ChatRecord();
        chatRecord.setHasRead(1);
        baseMapper.update(chatRecord, wrapper);
        List<ChatRecord> chatRecords = baseMapper.selectList(wrapper);
        if (chatRecords.size() <= 0) {
            return null;
        }
        return chatRecords;
    }
}
