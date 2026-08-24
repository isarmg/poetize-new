package com.ld.poetry.im.websocket;

import com.ld.poetry.im.http.entity.ImChatUserGroupMessage;
import com.ld.poetry.im.http.entity.ImChatUserMessage;
import com.ld.poetry.im.http.service.ImChatUserGroupMessageService;
import com.ld.poetry.im.http.service.ImChatUserMessageService;
import com.ld.poetry.utils.mail.MailSendUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;


@Component
@Slf4j
public class MessageCache {

    @Autowired
    private ImChatUserMessageService imChatUserMessageService;

    @Autowired
    private ImChatUserGroupMessageService imChatUserGroupMessageService;

    @Autowired
    private MailSendUtil mailSendUtil;

    private final List<ImChatUserMessage> userMessage = new ArrayList<>();

    private final List<ImChatUserGroupMessage> groupMessage = new ArrayList<>();

    private final Lock userMessageLock = new ReentrantLock();

    private final Lock groupMessageLock = new ReentrantLock();

    public void putUserMessage(ImChatUserMessage message) {
        userMessageLock.lock();
        try {
            userMessage.add(message);
        } finally {
            userMessageLock.unlock();
        }

        try {
            mailSendUtil.sendImMail(message);
        } catch (Exception e) {
            log.error("发送IM邮件失败：", e);
        }
    }

    public void putGroupMessage(ImChatUserGroupMessage message) {
        groupMessageLock.lock();
        try {
            groupMessage.add(message);
        } finally {
            groupMessageLock.unlock();
        }

    }

    @Scheduled(fixedDelay = 5000)
    public void saveUserMessage() {
        List<ImChatUserMessage> batch;
        userMessageLock.lock();
        try {
            if (CollectionUtils.isEmpty(userMessage)) {
                return;
            }
            batch = new ArrayList<>(userMessage);
            userMessage.clear();
        } finally {
            userMessageLock.unlock();
        }

        try {
            if (!imChatUserMessageService.saveBatch(batch)) {
                throw new IllegalStateException("单聊消息批量保存返回失败");
            }
        } catch (RuntimeException e) {
            restoreUserMessages(batch);
            log.error("单聊消息批量保存失败，已放回待保存队列", e);
        }
    }

    @Scheduled(fixedDelay = 10000)
    public void saveGroupMessage() {
        List<ImChatUserGroupMessage> batch;
        groupMessageLock.lock();
        try {
            if (CollectionUtils.isEmpty(groupMessage)) {
                return;
            }
            batch = new ArrayList<>(groupMessage);
            groupMessage.clear();
        } finally {
            groupMessageLock.unlock();
        }

        try {
            if (!imChatUserGroupMessageService.saveBatch(batch)) {
                throw new IllegalStateException("群聊消息批量保存返回失败");
            }
        } catch (RuntimeException e) {
            restoreGroupMessages(batch);
            log.error("群聊消息批量保存失败，已放回待保存队列", e);
        }
    }

    private void restoreUserMessages(List<ImChatUserMessage> failedBatch) {
        userMessageLock.lock();
        try {
            failedBatch.addAll(userMessage);
            userMessage.clear();
            userMessage.addAll(failedBatch);
        } finally {
            userMessageLock.unlock();
        }
    }

    private void restoreGroupMessages(List<ImChatUserGroupMessage> failedBatch) {
        groupMessageLock.lock();
        try {
            failedBatch.addAll(groupMessage);
            groupMessage.clear();
            groupMessage.addAll(failedBatch);
        } finally {
            groupMessageLock.unlock();
        }
    }
}
