package com.ld.poetry.im.http.controller;


import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ld.poetry.aop.LoginCheck;
import com.ld.poetry.config.PoetryResult;
import com.ld.poetry.entity.User;
import com.ld.poetry.im.http.entity.ImChatGroup;
import com.ld.poetry.im.http.entity.ImChatGroupUser;
import com.ld.poetry.im.http.entity.ImChatUserGroupMessage;
import com.ld.poetry.im.http.service.ImChatGroupService;
import com.ld.poetry.im.http.service.ImChatGroupUserService;
import com.ld.poetry.im.http.service.ImChatUserGroupMessageService;
import com.ld.poetry.im.http.vo.GroupUserVO;
import com.ld.poetry.im.websocket.ImConfigConst;
import com.ld.poetry.im.websocket.TioUtil;
import com.ld.poetry.im.websocket.TioWebsocketStarter;
import com.ld.poetry.handle.PoetryRuntimeException;
import com.ld.poetry.utils.CommonQuery;
import com.ld.poetry.utils.PoetryUtil;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tio.core.Tio;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <p>
 * 聊天群成员 前端控制器
 * </p>
 *
 * @author sara
 * @since 2021-12-02
 */
@RestController
@RequestMapping("/imChatGroupUser")
public class ImChatGroupUserController {

    @Autowired
    private ImChatGroupService imChatGroupService;

    @Autowired
    private ImChatGroupUserService imChatGroupUserService;

    @Autowired
    private ImChatUserGroupMessageService imChatUserGroupMessageService;

    @Autowired
    private CommonQuery commonQuery;

    /**
     * 申请加群
     */
    @GetMapping("/enterGroup")
    @LoginCheck
    public PoetryResult enterGroup(@RequestParam("id") Integer id, @RequestParam(value = "remark", required = false) String remark) {
        ImChatGroup chatGroup = imChatGroupService.getById(id);
        if (chatGroup == null) {
            return PoetryResult.fail("群组不存在！");
        }

        if (chatGroup.getGroupType() == null || chatGroup.getGroupType() == ImConfigConst.GROUP_TOPIC) {
            return PoetryResult.fail("话题无需申请！");
        }

        Integer userId = PoetryUtil.getUserId();

        LambdaQueryChainWrapper<ImChatGroupUser> lambdaQuery = imChatGroupUserService.lambdaQuery();
        lambdaQuery.eq(ImChatGroupUser::getGroupId, id);
        lambdaQuery.eq(ImChatGroupUser::getUserId, userId);
        ImChatGroupUser groupUser = lambdaQuery.one();
        if (groupUser != null) {
            return PoetryResult.fail("重复申请！");
        }

        ImChatGroupUser imChatGroupUser = new ImChatGroupUser();
        imChatGroupUser.setGroupId(id);
        imChatGroupUser.setUserId(userId);
        if (StringUtils.hasText(remark)) {
            imChatGroupUser.setRemark(remark);
        }
        if (Boolean.TRUE.equals(chatGroup.getInType())) {
            imChatGroupUser.setUserStatus(ImConfigConst.GROUP_USER_STATUS_NOT_VERIFY);
        } else {
            imChatGroupUser.setUserStatus(ImConfigConst.GROUP_USER_STATUS_PASS);
        }
        boolean isSuccess = imChatGroupUserService.save(imChatGroupUser);
        if (isSuccess && !Boolean.TRUE.equals(chatGroup.getInType())) {
            TioWebsocketStarter tioWebsocketStarter = TioUtil.getTio();
            if (tioWebsocketStarter != null) {
                Tio.bindGroup(tioWebsocketStarter.getServerTioConfig(), String.valueOf(userId), String.valueOf(id));
            }
        }
        return PoetryResult.success();
    }

    /**
     * 改变群组用户状态
     * <p>
     * 用户状态[-1:审核不通过或者踢出群组，1:审核通过，2:禁言]
     */
    @GetMapping("/changeUserStatus")
    @LoginCheck
    public PoetryResult changeUserStatus(@RequestParam("groupId") Integer groupId,
                                         @RequestParam("userId") Integer userId,
                                         @RequestParam("userStatus") Integer userStatus,
                                         @RequestParam("oldUserStatus") Integer oldUserStatus) {
        if (groupId == null || userId == null || !isValidNewUserStatus(userStatus) || !isValidOldUserStatus(oldUserStatus)) {
            return PoetryResult.fail("群成员状态参数不合法！");
        }
        ImChatGroup chatGroup = imChatGroupService.getById(groupId);
        if (chatGroup == null) {
            return PoetryResult.fail("群组不存在！");
        }

        if (chatGroup.getGroupType() == null || chatGroup.getGroupType() == ImConfigConst.GROUP_TOPIC) {
            return PoetryResult.fail("话题无需操作！");
        }

        Integer currentUserId = PoetryUtil.getUserId();
        LambdaQueryChainWrapper<ImChatGroupUser> lambdaQuery = imChatGroupUserService.lambdaQuery();
        lambdaQuery.eq(ImChatGroupUser::getGroupId, groupId);
        lambdaQuery.eq(ImChatGroupUser::getUserId, currentUserId);
        lambdaQuery.eq(ImChatGroupUser::getAdminFlag, ImConfigConst.ADMIN_FLAG_TRUE);
        ImChatGroupUser groupUser = lambdaQuery.one();
        if (groupUser == null) {
            return PoetryResult.fail("没有审核权限！");
        }

        boolean isSuccess;
        if (userStatus == ImConfigConst.GROUP_USER_STATUS_BAN) {
            LambdaUpdateChainWrapper<ImChatGroupUser> lambdaUpdate = imChatGroupUserService.lambdaUpdate();
            lambdaUpdate.eq(ImChatGroupUser::getGroupId, groupId);
            lambdaUpdate.eq(ImChatGroupUser::getUserId, userId);
            lambdaUpdate.eq(ImChatGroupUser::getAdminFlag, ImConfigConst.ADMIN_FLAG_FALSE);
            lambdaUpdate.eq(ImChatGroupUser::getUserStatus, oldUserStatus);
            isSuccess = lambdaUpdate.remove();
        } else {
            LambdaUpdateChainWrapper<ImChatGroupUser> lambdaUpdate = imChatGroupUserService.lambdaUpdate();
            lambdaUpdate.eq(ImChatGroupUser::getGroupId, groupId);
            lambdaUpdate.eq(ImChatGroupUser::getUserId, userId);
            lambdaUpdate.eq(ImChatGroupUser::getAdminFlag, ImConfigConst.ADMIN_FLAG_FALSE);
            lambdaUpdate.eq(ImChatGroupUser::getUserStatus, oldUserStatus);
            lambdaUpdate.set(ImChatGroupUser::getUserStatus, userStatus);
            if (userStatus == ImConfigConst.GROUP_USER_STATUS_PASS) {
                lambdaUpdate.set(ImChatGroupUser::getVerifyUserId, currentUserId);
            }
            isSuccess = lambdaUpdate.update();
        }
        if (isSuccess && userStatus == ImConfigConst.GROUP_USER_STATUS_PASS) {
            TioWebsocketStarter tioWebsocketStarter = TioUtil.getTio();
            if (tioWebsocketStarter != null) {
                Tio.bindGroup(tioWebsocketStarter.getServerTioConfig(), String.valueOf(userId), String.valueOf(groupId));
            }
        } else if (isSuccess && userStatus.intValue() == ImConfigConst.GROUP_USER_STATUS_BAN &&
                (oldUserStatus.intValue() == ImConfigConst.GROUP_USER_STATUS_PASS ||
                        oldUserStatus.intValue() == ImConfigConst.GROUP_USER_STATUS_SILENCE)) {
            TioWebsocketStarter tioWebsocketStarter = TioUtil.getTio();
            if (tioWebsocketStarter != null) {
                Tio.unbindGroup(tioWebsocketStarter.getServerTioConfig(), String.valueOf(userId), String.valueOf(groupId));
            }
        }

        if (isSuccess) {
            return PoetryResult.success();
        } else {
            return PoetryResult.fail("修改失败！");
        }
    }

    /**
     * 设置群组管理员
     * <p>
     * adminFlag = true 是管理员
     * adminFlag = false 不是管理员
     */
    @GetMapping("/changeAdmin")
    @LoginCheck
    public PoetryResult changeAdmin(@RequestParam("groupId") Integer groupId,
                                    @RequestParam("userId") Integer userId,
                                    @RequestParam("adminFlag") Boolean adminFlag) {
        if (groupId == null || userId == null || adminFlag == null) {
            return PoetryResult.fail("参数不合法！");
        }
        ImChatGroup chatGroup = imChatGroupService.getById(groupId);
        if (chatGroup == null) {
            return PoetryResult.fail("群组不存在！");
        }

        if (chatGroup.getGroupType() == null || chatGroup.getGroupType() == ImConfigConst.GROUP_TOPIC) {
            return PoetryResult.fail("话题无需操作！");
        }

        Integer currentUserId = PoetryUtil.getUserId();
        if (!currentUserId.equals(chatGroup.getMasterUserId())) {
            return PoetryResult.fail("群主才能设置管理员！");
        }
        if (userId.equals(chatGroup.getMasterUserId()) && !adminFlag) {
            return PoetryResult.fail("不能取消群主的管理员身份！");
        }

        LambdaUpdateChainWrapper<ImChatGroupUser> lambdaUpdate = imChatGroupUserService.lambdaUpdate();
        lambdaUpdate.eq(ImChatGroupUser::getGroupId, groupId);
        lambdaUpdate.eq(ImChatGroupUser::getUserId, userId);
        lambdaUpdate.in(ImChatGroupUser::getUserStatus,
                ImConfigConst.GROUP_USER_STATUS_PASS, ImConfigConst.GROUP_USER_STATUS_SILENCE);
        lambdaUpdate.set(ImChatGroupUser::getAdminFlag, adminFlag);

        return lambdaUpdate.update() ? PoetryResult.success() : PoetryResult.fail("目标用户不是本群有效成员！");
    }

    /**
     * 退群
     */
    @GetMapping("/quitGroup")
    @LoginCheck
    @Transactional(rollbackFor = Exception.class)
    public PoetryResult quitGroup(@RequestParam("id") Integer id) {
        ImChatGroup chatGroup = imChatGroupService.getById(id);
        if (chatGroup == null) {
            return PoetryResult.fail("群组不存在！");
        }

        if (chatGroup.getGroupType() == null || chatGroup.getGroupType() == ImConfigConst.GROUP_TOPIC) {
            return PoetryResult.fail("话题无需操作！");
        }

        Integer userId = PoetryUtil.getUserId();

        if (chatGroup.getMasterUserId().intValue() == userId.intValue()) {
            //转让群
            LambdaQueryChainWrapper<ImChatGroupUser> wrapper = imChatGroupUserService.lambdaQuery();
            wrapper.eq(ImChatGroupUser::getGroupId, id);
            wrapper.ne(ImChatGroupUser::getUserId, userId);
            wrapper.in(ImChatGroupUser::getUserStatus,
                    ImConfigConst.GROUP_USER_STATUS_PASS, ImConfigConst.GROUP_USER_STATUS_SILENCE);
            wrapper.orderByDesc(ImChatGroupUser::getAdminFlag);
            wrapper.orderByAsc(ImChatGroupUser::getCreateTime);
            wrapper.last("limit 1");
            ImChatGroupUser one = wrapper.one();

            if (one == null) {
                //除了群主没别人，直接删除
                boolean removed = imChatGroupService.lambdaUpdate()
                        .eq(ImChatGroup::getId, id)
                        .eq(ImChatGroup::getMasterUserId, userId)
                        .remove();
                if (!removed) {
                    throw new PoetryRuntimeException("群组删除失败！");
                }
                imChatGroupUserService.lambdaUpdate().eq(ImChatGroupUser::getGroupId, id).remove();
                imChatUserGroupMessageService.lambdaUpdate()
                        .eq(ImChatUserGroupMessage::getGroupId, id).remove();
                TioWebsocketStarter starter = TioUtil.getTio();
                if (starter != null) {
                    Tio.removeGroup(starter.getServerTioConfig(), String.valueOf(id), "remove group");
                }
                return PoetryResult.success();
            } else {
                LambdaUpdateChainWrapper<ImChatGroup> groupUpdate = imChatGroupService.lambdaUpdate();
                groupUpdate.eq(ImChatGroup::getId, id);
                groupUpdate.eq(ImChatGroup::getMasterUserId, userId);
                groupUpdate.set(ImChatGroup::getMasterUserId, one.getUserId());
                if (!groupUpdate.update()) {
                    throw new PoetryRuntimeException("群主转让失败！");
                }
                LambdaUpdateChainWrapper<ImChatGroupUser> groupUserUpdate = imChatGroupUserService.lambdaUpdate();
                groupUserUpdate.eq(ImChatGroupUser::getId, one.getId());
                groupUserUpdate.eq(ImChatGroupUser::getGroupId, id);
                groupUserUpdate.set(ImChatGroupUser::getAdminFlag, ImConfigConst.ADMIN_FLAG_TRUE);
                if (!groupUserUpdate.update()) {
                    throw new PoetryRuntimeException("新群主管理员状态更新失败！");
                }
            }
        }

        LambdaUpdateChainWrapper<ImChatGroupUser> lambdaUpdate = imChatGroupUserService.lambdaUpdate();
        lambdaUpdate.eq(ImChatGroupUser::getGroupId, id);
        lambdaUpdate.eq(ImChatGroupUser::getUserId, userId);
        boolean isSuccess = lambdaUpdate.remove();
        if (!isSuccess) {
            throw new PoetryRuntimeException("退群失败！");
        }
        TioWebsocketStarter tioWebsocketStarter = TioUtil.getTio();
        if (tioWebsocketStarter != null) {
            Tio.unbindGroup(tioWebsocketStarter.getServerTioConfig(), String.valueOf(userId), String.valueOf(id));
        }
        return PoetryResult.success();
    }

    /**
     * 群管理员查询群用户
     */
    @GetMapping("/getGroupUserByStatus")
    @LoginCheck
    public PoetryResult<Page> getGroupUserByStatus(@RequestParam(value = "groupId", required = false) Integer groupId,
                                                   @RequestParam(value = "userStatus", required = false) Integer userStatus,
                                                   @RequestParam(value = "current", defaultValue = "1") Long current,
                                                   @RequestParam(value = "size", defaultValue = "20") Long size) {
        Integer userId = PoetryUtil.getUserId();
        Page<ImChatGroupUser> page = new Page<>();
        page.setCurrent(PoetryUtil.normalizePageCurrent(current));
        page.setSize(PoetryUtil.normalizePageSize(size, 20L));
        LambdaQueryChainWrapper<ImChatGroupUser> lambdaQuery = imChatGroupUserService.lambdaQuery();
        if (groupId != null) {
            ImChatGroup chatGroup = imChatGroupService.getById(groupId);
            if (chatGroup == null) {
                return PoetryResult.fail("群组不存在！");
            }

            if (chatGroup.getGroupType() == null
                    || chatGroup.getGroupType() == ImConfigConst.GROUP_TOPIC) {
                return PoetryResult.fail("话题没有用户！");
            }

            LambdaQueryChainWrapper<ImChatGroupUser> groupLambdaQuery = imChatGroupUserService.lambdaQuery();
            groupLambdaQuery.eq(ImChatGroupUser::getGroupId, groupId);
            groupLambdaQuery.eq(ImChatGroupUser::getUserId, userId);
            groupLambdaQuery.eq(ImChatGroupUser::getAdminFlag, ImConfigConst.ADMIN_FLAG_TRUE);
            ImChatGroupUser groupUser = groupLambdaQuery.one();
            if (groupUser == null) {
                return PoetryResult.fail("没有审核权限！");
            }
            lambdaQuery.eq(ImChatGroupUser::getGroupId, groupId);
        } else {
            LambdaQueryChainWrapper<ImChatGroupUser> userLambdaQuery = imChatGroupUserService.lambdaQuery();
            userLambdaQuery.eq(ImChatGroupUser::getUserId, userId);
            userLambdaQuery.eq(ImChatGroupUser::getAdminFlag, ImConfigConst.ADMIN_FLAG_TRUE);
            List<ImChatGroupUser> groupUsers = userLambdaQuery.list();
            if (CollectionUtils.isEmpty(groupUsers)) {
                // 该用户没有管理任何群
                return PoetryResult.success();
            } else {
                List<Integer> groupIds = groupUsers.stream().map(ImChatGroupUser::getGroupId).collect(Collectors.toList());
                lambdaQuery.in(ImChatGroupUser::getGroupId, groupIds);
            }
        }

        if (userStatus != null) {
            lambdaQuery.eq(ImChatGroupUser::getUserStatus, userStatus);
        }

        lambdaQuery.orderByDesc(ImChatGroupUser::getCreateTime).page(page);

        List<GroupUserVO> groupUserVOList = new ArrayList<>(page.getRecords().size());
        List<ImChatGroupUser> records = page.getRecords();
        Map<Integer, List<ImChatGroupUser>> map = records.stream().collect(Collectors.groupingBy(ImChatGroupUser::getGroupId));
        List<ImChatGroup> groups = imChatGroupService.lambdaQuery().select(ImChatGroup::getId, ImChatGroup::getGroupName).in(ImChatGroup::getId, map.keySet()).list();
        Map<Integer, String> collect = groups.stream().collect(Collectors.toMap(ImChatGroup::getId, ImChatGroup::getGroupName));
        map.forEach((key, value) -> {
            String groupName = collect.get(key);
            value.forEach(g -> {
                GroupUserVO groupUserVO = new GroupUserVO();
                BeanUtils.copyProperties(g, groupUserVO);
                groupUserVO.setGroupName(groupName);
                User user = commonQuery.getUser(groupUserVO.getUserId());
                if (user != null) {
                    groupUserVO.setUsername(user.getUsername());
                    groupUserVO.setAvatar(user.getAvatar());
                }
                groupUserVOList.add(groupUserVO);
            });
        });

        Page<GroupUserVO> result = new Page<>();
        result.setRecords(groupUserVOList);
        result.setTotal(page.getTotal());
        result.setCurrent(page.getCurrent());
        result.setSize(page.getSize());
        return PoetryResult.success(result);
    }

    /**
     * 群用户查询群用户
     */
    @GetMapping("/getGroupUser")
    @LoginCheck
    public PoetryResult<Page> getGroupUser(@RequestParam("groupId") Integer groupId,
                                           @RequestParam(value = "current", defaultValue = "1") Long current,
                                           @RequestParam(value = "size", defaultValue = "20") Long size) {
        ImChatGroup chatGroup = imChatGroupService.getById(groupId);
        if (chatGroup == null) {
            return PoetryResult.fail("群组不存在！");
        }

        if (chatGroup.getGroupType() == null
                || chatGroup.getGroupType() == ImConfigConst.GROUP_TOPIC) {
            return PoetryResult.fail("话题没有用户！");
        }

        Integer userId = PoetryUtil.getUserId();
        LambdaQueryChainWrapper<ImChatGroupUser> wrapper = imChatGroupUserService.lambdaQuery();
        wrapper.eq(ImChatGroupUser::getUserId, userId);
        wrapper.eq(ImChatGroupUser::getGroupId, groupId);
        wrapper.in(ImChatGroupUser::getUserStatus, ImConfigConst.GROUP_USER_STATUS_PASS, ImConfigConst.GROUP_USER_STATUS_SILENCE);
        long count = wrapper.count();
        if (count < 1) {
            return PoetryResult.fail("未加群！");
        }

        Page<ImChatGroupUser> page = new Page<>();
        page.setCurrent(PoetryUtil.normalizePageCurrent(current));
        page.setSize(PoetryUtil.normalizePageSize(size, 20L));
        LambdaQueryChainWrapper<ImChatGroupUser> lambdaQuery = imChatGroupUserService.lambdaQuery();
        lambdaQuery.eq(ImChatGroupUser::getGroupId, groupId);
        lambdaQuery.in(ImChatGroupUser::getUserStatus, ImConfigConst.GROUP_USER_STATUS_PASS, ImConfigConst.GROUP_USER_STATUS_SILENCE);
        lambdaQuery.orderByAsc(ImChatGroupUser::getCreateTime).page(page);

        List<GroupUserVO> groupUserVOList = new ArrayList<>(page.getRecords().size());
        List<ImChatGroupUser> records = page.getRecords();
        records.forEach(g -> {
            GroupUserVO groupUserVO = new GroupUserVO();
            BeanUtils.copyProperties(g, groupUserVO);
            groupUserVO.setGroupName(chatGroup.getGroupName());
            User user = commonQuery.getUser(groupUserVO.getUserId());
            if (user != null) {
                groupUserVO.setUsername(user.getUsername());
                groupUserVO.setAvatar(user.getAvatar());
            }
            groupUserVOList.add(groupUserVO);
        });

        Page<GroupUserVO> result = new Page<>();
        result.setRecords(groupUserVOList);
        result.setTotal(page.getTotal());
        result.setCurrent(page.getCurrent());
        result.setSize(page.getSize());
        return PoetryResult.success(result);
    }

    private boolean isValidNewUserStatus(Integer status) {
        return status != null && (status == ImConfigConst.GROUP_USER_STATUS_BAN
                || status == ImConfigConst.GROUP_USER_STATUS_PASS
                || status == ImConfigConst.GROUP_USER_STATUS_SILENCE);
    }

    private boolean isValidOldUserStatus(Integer status) {
        return status != null && (status == ImConfigConst.GROUP_USER_STATUS_NOT_VERIFY
                || status == ImConfigConst.GROUP_USER_STATUS_PASS
                || status == ImConfigConst.GROUP_USER_STATUS_SILENCE);
    }
}
