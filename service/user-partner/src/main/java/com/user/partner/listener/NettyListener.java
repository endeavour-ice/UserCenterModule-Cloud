package com.user.partner.listener;

import com.rabbitmq.client.Channel;
import com.user.model.domain.ChatRecord;
import com.user.model.domain.TeamChatRecord;
import com.user.partner.service.IChatRecordService;
import com.user.partner.service.ITeamChatRecordService;
import com.user.rabbitmq.config.mq.AckMode;
import com.user.rabbitmq.config.mq.MqClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * @author ice
 * @date 2022/8/20 16:08
 */
@Component
@Slf4j
public class NettyListener {
    @Autowired
    private IChatRecordService chatRecordService;
    @Autowired
    private ITeamChatRecordService teamChatRecordService;

    @RabbitListener(queues = MqClient.NETTY_QUEUE,ackMode = AckMode.MANUAL)
    public void SaveChatRecord(Message message, Channel channel, ChatRecord chatRecord) {
        try {
            if (chatRecord != null) {
                boolean save = chatRecordService.save(chatRecord);
                if (!save) {
                    log.error("保存聊天记录失败");
                }
            }else {
                log.error("保存聊天记录失败");

            }
            channel.basicAck(message.getMessageProperties().getDeliveryTag(),false);
        } catch (Exception e) {
            log.error("保存聊天记录失败" + e.getMessage());
            try {
                channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, false);
            } catch (IOException ex) {
                log.error("消息队列拒绝失败" + e.getMessage());
            }
        }
    }

    @RabbitListener(queues = MqClient.TEAM_QUEUE)
    public void SaveTeamChatRecord(Message message, Channel channel, TeamChatRecord chatRecord) {
        if (chatRecord != null) {
            boolean save = teamChatRecordService.save(chatRecord);
            if (!save) {
                log.error("保存队伍聊天记录失败...");
            }
        }else {
            log.error("保存队伍聊天记录失败...");
        }
    }

}
