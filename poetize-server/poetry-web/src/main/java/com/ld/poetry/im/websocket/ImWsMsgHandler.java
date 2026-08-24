package com.ld.poetry.im.websocket;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.ld.poetry.entity.User;
import com.ld.poetry.im.http.entity.ImChatGroup;
import com.ld.poetry.im.http.entity.ImChatGroupUser;
import com.ld.poetry.im.http.entity.ImChatUserFriend;
import com.ld.poetry.im.http.entity.ImChatUserGroupMessage;
import com.ld.poetry.im.http.entity.ImChatUserMessage;
import com.ld.poetry.im.http.service.ImChatGroupService;
import com.ld.poetry.im.http.service.ImChatGroupUserService;
import com.ld.poetry.im.http.service.ImChatUserFriendService;
import com.ld.poetry.im.http.service.ImChatUserMessageService;
import com.ld.poetry.constants.CommonConst;
import com.ld.poetry.utils.CommonQuery;
import com.ld.poetry.utils.cache.PoetryCache;
import com.ld.poetry.utils.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.tio.core.ChannelContext;
import org.tio.core.Tio;
import org.tio.http.common.HeaderName;
import org.tio.http.common.HeaderValue;
import org.tio.http.common.HttpRequest;
import org.tio.http.common.HttpResponse;
import org.tio.utils.lock.SetWithLock;
import org.tio.websocket.common.WsRequest;
import org.tio.websocket.common.WsResponse;
import org.tio.websocket.server.handler.IWsMsgHandler;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class ImWsMsgHandler implements IWsMsgHandler {
    private static final String AUTHENTICATED_USER_ID = "authenticatedUserId";

    private static final String WEBSOCKET_PROTOCOL_HEADER = "sec-websocket-protocol";

    private static final int MAX_MESSAGE_LENGTH = 1000;


    @Autowired
    private ImChatGroupUserService imChatGroupUserService;

    @Autowired
    private ImChatGroupService imChatGroupService;

    @Autowired
    private ImChatUserFriendService imChatUserFriendService;

    @Autowired
    private ImChatUserMessageService imChatUserMessageService;

    @Autowired
    private MessageCache messageCache;

    @Autowired
    private CommonQuery commonQuery;

    /**
     * 握手时走这个方法，业务可以在这里获取cookie，request等
     * 对httpResponse参数进行补充并返回，如果返回null表示不想和对方建立连接
     * 对于大部分业务，该方法只需要一行代码：return httpResponse;
     */
    @Override
    public HttpResponse handshake(HttpRequest httpRequest, HttpResponse httpResponse, ChannelContext channelContext) {
        User user = getAuthenticatedUser(httpRequest);
        if (user == null) {
            return null;
        }

        // 浏览器 WebSocket 无法自定义 Authorization 请求头，使用子协议传递
        // 会话令牌并在响应中回显，避免令牌出现在 URL/访问日志。
        String token = getWebSocketToken(httpRequest);
        httpResponse.addHeader(HeaderName.Sec_Websocket_Protocol, HeaderValue.from(token));

        log.info("握手成功：用户ID：{}, 用户名：{}", user.getId(), user.getUsername());

        return httpResponse;
    }

    /**
     * 握手成功后触发该方法
     */
    @Override
    public void onAfterHandshaked(HttpRequest httpRequest, HttpResponse httpResponse, ChannelContext channelContext) {
        User user = getAuthenticatedUser(httpRequest);
        if (user == null) {
            Tio.remove(channelContext, "登录状态已失效");
            return;
        }
        Tio.closeUser(channelContext.tioConfig, user.getId().toString(), null);
        Tio.bindUser(channelContext, user.getId().toString());
        channelContext.set(AUTHENTICATED_USER_ID, user.getId());

        List<ImChatUserMessage> userMessages = imChatUserMessageService.lambdaQuery().eq(ImChatUserMessage::getToId, user.getId())
                .eq(ImChatUserMessage::getMessageStatus, ImConfigConst.USER_MESSAGE_STATUS_FALSE)
                .orderByAsc(ImChatUserMessage::getCreateTime).list();

        if (!CollectionUtils.isEmpty(userMessages)) {
            List<Long> ids = new ArrayList<>();
            userMessages.forEach(userMessage -> {
                ids.add(userMessage.getId());
                ImMessage imMessage = new ImMessage();
                imMessage.setContent(userMessage.getContent());
                imMessage.setFromId(userMessage.getFromId());
                imMessage.setToId(userMessage.getToId());
                imMessage.setMessageType(ImEnum.MESSAGE_TYPE_MSG_SINGLE.getCode());
                User friend = commonQuery.getUser(userMessage.getFromId());
                if (friend != null) {
                    imMessage.setAvatar(friend.getAvatar());
                }
                WsResponse wsResponse = WsResponse.fromText(JSON.toJSONString(imMessage), ImConfigConst.CHARSET);
                Tio.sendToUser(channelContext.tioConfig, userMessage.getToId().toString(), wsResponse);
            });
            imChatUserMessageService.lambdaUpdate().in(ImChatUserMessage::getId, ids)
                    .set(ImChatUserMessage::getMessageStatus, ImConfigConst.USER_MESSAGE_STATUS_TRUE).update();

        }

        LambdaQueryChainWrapper<ImChatGroupUser> lambdaQuery = imChatGroupUserService.lambdaQuery();
        lambdaQuery.select(ImChatGroupUser::getGroupId);
        lambdaQuery.eq(ImChatGroupUser::getUserId, user.getId());
        lambdaQuery.in(ImChatGroupUser::getUserStatus, ImConfigConst.GROUP_USER_STATUS_PASS, ImConfigConst.GROUP_USER_STATUS_SILENCE);
        List<ImChatGroupUser> groupUsers = lambdaQuery.list();
        if (!CollectionUtils.isEmpty(groupUsers)) {
            groupUsers.forEach(groupUser -> Tio.bindGroup(channelContext, groupUser.getGroupId().toString()));
        }
    }

    @Override
    public Object onBytes(WsRequest wsRequest, byte[] bytes, ChannelContext channelContext) {
        return null;
    }

    @Override
    public Object onClose(WsRequest wsRequest, byte[] bytes, ChannelContext channelContext) {
        Tio.remove(channelContext, "连接关闭");
        return null;
    }

    @Override
    public Object onText(WsRequest wsRequest, String text, ChannelContext channelContext) {
        Object authenticatedUserId = channelContext.get(AUTHENTICATED_USER_ID);
        if (!(authenticatedUserId instanceof Integer senderId)) {
            Tio.remove(channelContext, "未认证的连接");
            return null;
        }
        if (!StringUtils.hasText(text) || text.length() > 8192) {
            return null;
        }
        try {
            ImMessage imMessage = JSON.parseObject(text, ImMessage.class);
            if (imMessage == null || imMessage.getMessageType() == null
                    || !StringUtils.hasText(imMessage.getContent())) {
                return null;
            }

            String content = StringUtil.removeHtml(imMessage.getContent());
            if (!StringUtils.hasText(content) || content.length() > MAX_MESSAGE_LENGTH) {
                return null;
            }
            User sender = commonQuery.getUser(senderId);
            if (sender == null) {
                Tio.remove(channelContext, "用户不存在");
                return null;
            }

            imMessage.setContent(content);
            imMessage.setFromId(senderId);
            imMessage.setAvatar(sender.getAvatar());
            imMessage.setUsername(sender.getUsername());

            if (imMessage.getMessageType().equals(ImEnum.MESSAGE_TYPE_MSG_SINGLE.getCode())) {
                if (imMessage.getToId() == null || imMessage.getToId() <= 0
                        || imMessage.getToId().equals(senderId)
                        || commonQuery.getUser(imMessage.getToId()) == null) {
                    return null;
                }
                long friendshipCount = imChatUserFriendService.lambdaQuery()
                        .eq(ImChatUserFriend::getUserId, senderId)
                        .eq(ImChatUserFriend::getFriendId, imMessage.getToId())
                        .eq(ImChatUserFriend::getFriendStatus, ImConfigConst.FRIEND_STATUS_PASS)
                        .count();
                if (friendshipCount < 1) {
                    log.warn("拒绝非好友私信：userId={}, toId={}", senderId, imMessage.getToId());
                    return null;
                }
                //单聊
                ImChatUserMessage userMessage = new ImChatUserMessage();
                userMessage.setFromId(senderId);
                userMessage.setToId(imMessage.getToId());
                userMessage.setContent(imMessage.getContent());
                userMessage.setCreateTime(LocalDateTime.now());

                WsResponse wsResponse = WsResponse.fromText(JSON.toJSONString(imMessage), ImConfigConst.CHARSET);
                SetWithLock<ChannelContext> setWithLock = Tio.getByUserid(channelContext.tioConfig, imMessage.getToId().toString());
                if (setWithLock != null && setWithLock.size() > 0) {
                    Tio.sendToUser(channelContext.tioConfig, imMessage.getToId().toString(), wsResponse);
                    userMessage.setMessageStatus(ImConfigConst.USER_MESSAGE_STATUS_TRUE);
                } else {
                    userMessage.setMessageStatus(ImConfigConst.USER_MESSAGE_STATUS_FALSE);
                }
                messageCache.putUserMessage(userMessage);
                if (!imMessage.getToId().equals(senderId)) {
                    Tio.sendToUser(channelContext.tioConfig, senderId.toString(), wsResponse);
                }
            } else if (imMessage.getMessageType().equals(ImEnum.MESSAGE_TYPE_MSG_GROUP.getCode())) {
                if (imMessage.getGroupId() == null) {
                    return null;
                }
                ImChatGroup group = imChatGroupService.getById(imMessage.getGroupId());
                if (group == null || group.getGroupType() == null) {
                    return null;
                }
                if (group.getGroupType() == ImConfigConst.GROUP_COMMON) {
                    long membershipCount = imChatGroupUserService.lambdaQuery()
                            .eq(ImChatGroupUser::getGroupId, imMessage.getGroupId())
                            .eq(ImChatGroupUser::getUserId, senderId)
                            .eq(ImChatGroupUser::getUserStatus, ImConfigConst.GROUP_USER_STATUS_PASS)
                            .count();
                    if (membershipCount < 1) {
                        log.warn("拒绝非群成员或禁言用户发送群消息：userId={}, groupId={}", senderId, imMessage.getGroupId());
                        return null;
                    }
                } else if (group.getGroupType() == ImConfigConst.GROUP_TOPIC) {
                    Tio.bindGroup(channelContext, imMessage.getGroupId().toString());
                } else {
                    return null;
                }
                //群聊
                ImChatUserGroupMessage groupMessage = new ImChatUserGroupMessage();
                groupMessage.setContent(imMessage.getContent());
                groupMessage.setFromId(senderId);
                groupMessage.setGroupId(imMessage.getGroupId());
                groupMessage.setCreateTime(LocalDateTime.now());
                messageCache.putGroupMessage(groupMessage);

                WsResponse wsResponse = WsResponse.fromText(JSON.toJSONString(imMessage), ImConfigConst.CHARSET);
                SetWithLock<ChannelContext> setWithLock = Tio.getByGroup(channelContext.tioConfig, imMessage.getGroupId().toString());
                if (setWithLock != null && setWithLock.size() > 0) {
                    Tio.sendToGroup(channelContext.tioConfig, imMessage.getGroupId().toString(), wsResponse);
                }
            }
        } catch (Exception e) {
            log.warn("处理 WebSocket 消息失败", e);
        }
        //返回值是要发送给客户端的内容，一般都是返回null
        return null;
    }

    private User getAuthenticatedUser(HttpRequest httpRequest) {
        String token = getWebSocketToken(httpRequest);
        if (!StringUtils.hasText(token) || !token.startsWith(CommonConst.USER_ACCESS_TOKEN)) {
            return null;
        }
        Object cached = PoetryCache.get(token);
        if (!(cached instanceof User user) || !Boolean.TRUE.equals(user.getUserStatus())) {
            return null;
        }
        Object mappedToken = PoetryCache.get(CommonConst.USER_TOKEN + user.getId());
        return token.equals(mappedToken) ? user : null;
    }

    private String getWebSocketToken(HttpRequest httpRequest) {
        String protocol = httpRequest.getHeader(WEBSOCKET_PROTOCOL_HEADER);
        if (!StringUtils.hasText(protocol) || protocol.indexOf(',') >= 0) {
            return null;
        }
        return protocol.trim();
    }
}
