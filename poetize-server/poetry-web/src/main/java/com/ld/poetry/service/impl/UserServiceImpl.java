package com.ld.poetry.service.impl;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ld.poetry.config.PoetryResult;
import com.ld.poetry.constants.CommonConst;
import com.ld.poetry.dao.UserMapper;
import com.ld.poetry.dao.LabelMapper;
import com.ld.poetry.entity.Label;
import com.ld.poetry.entity.User;
import com.ld.poetry.entity.WebInfo;
import com.ld.poetry.entity.WeiYan;
import com.ld.poetry.enums.PoetryEnum;
import com.ld.poetry.handle.PoetryRuntimeException;
import com.ld.poetry.im.http.dao.ImChatGroupUserMapper;
import com.ld.poetry.im.http.dao.ImChatUserFriendMapper;
import com.ld.poetry.im.http.entity.ImChatGroupUser;
import com.ld.poetry.im.http.entity.ImChatUserFriend;
import com.ld.poetry.im.websocket.ImConfigConst;
import com.ld.poetry.im.websocket.TioUtil;
import com.ld.poetry.im.websocket.TioWebsocketStarter;
import com.ld.poetry.service.UserService;
import com.ld.poetry.service.WeiYanService;
import com.ld.poetry.utils.*;
import com.ld.poetry.utils.cache.PoetryCache;
import com.ld.poetry.utils.mail.MailUtil;
import com.ld.poetry.vo.BaseRequestVO;
import com.ld.poetry.vo.UserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import org.tio.core.Tio;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * <p>
 * 用户信息表 服务实现类
 * </p>
 *
 * @author sara
 * @since 2021-08-12
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String LOGIN_FAILURE_PREFIX = "login_failure_";
    private static final int LOGIN_FAILURE_LIMIT = 10;
    private static final long LOGIN_FAILURE_EXPIRE = 900L;
    private static final Object SESSION_LOCK = new Object();


    @Autowired
    private WeiYanService weiYanService;

    @Autowired
    private ImChatGroupUserMapper imChatGroupUserMapper;

    @Autowired
    private ImChatUserFriendMapper imChatUserFriendMapper;

    @Autowired
    private MailUtil mailUtil;

    @Autowired
    private LabelMapper labelMapper;

    @Value("${user.code.format}")
    private String codeFormat;

    @Override
    public PoetryResult<UserVO> login(String account, String password, Boolean isAdmin) {
        if (!StringUtils.hasText(account) || account.length() > 128
                || !StringUtils.hasText(password) || password.length() > 512 || isAdmin == null) {
            return PoetryResult.fail("账号/密码错误，请重新输入！");
        }
        account = account.trim();
        String remoteAddress = PoetryUtil.getIpAddr(PoetryUtil.getRequest());
        String accountDigest = DigestUtils.md5DigestAsHex(
                account.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        String failureKey = LOGIN_FAILURE_PREFIX + remoteAddress + "_" + accountDigest;
        if (PoetryCache.getCount(failureKey) >= LOGIN_FAILURE_LIMIT) {
            return PoetryResult.fail("账号/密码错误，请重新输入！");
        }
        String plainPassword = decryptPassword(password);

        List<User> candidates = lambdaQuery().and(wrapper -> wrapper
                        .eq(User::getUsername, account)
                        .or()
                        .eq(User::getEmail, account)
                        .or()
                        .eq(User::getPhoneNumber, account))
                .list();
        User one = candidates.stream()
                .filter(candidate -> passwordMatches(plainPassword, candidate.getPassword()))
                .findFirst()
                .orElse(null);

        if (one == null) {
            PoetryCache.increment(failureKey, LOGIN_FAILURE_EXPIRE);
            return PoetryResult.fail("账号/密码错误，请重新输入！");
        }
        PoetryCache.remove(failureKey);

        if (!Boolean.TRUE.equals(one.getUserStatus())) {
            return PoetryResult.fail("账号被冻结！");
        }

        if (isLegacyMd5(one.getPassword()) && isBcryptCompatible(plainPassword)) {
            String oldPassword = one.getPassword();
            String upgradedPassword = BCrypt.hashpw(plainPassword);
            boolean upgraded = lambdaUpdate()
                    .eq(User::getId, one.getId())
                    .eq(User::getPassword, oldPassword)
                    .set(User::getPassword, upgradedPassword)
                    .update();
            if (upgraded) {
                one.setPassword(upgradedPassword);
                PoetryCache.remove(CommonConst.USER_CACHE + one.getId());
            }
        }

        String accessToken;
        synchronized (SESSION_LOCK) {
            User current = lambdaQuery().eq(User::getId, one.getId()).one();
            if (current == null || !Boolean.TRUE.equals(current.getUserStatus())
                    || !passwordMatches(plainPassword, current.getPassword())) {
                return PoetryResult.fail("账号/密码错误，请重新输入！");
            }
            if (isAdmin && current.getUserType() != PoetryEnum.USER_TYPE_ADMIN.getCode()
                    && current.getUserType() != PoetryEnum.USER_TYPE_DEV.getCode()) {
                return PoetryResult.fail("请输入管理员账号！");
            }
            one = current;
            accessToken = getOrCreateSessionTokenLocked(one, isAdmin);
        }


        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(one, userVO);
        userVO.setPassword(null);
        if (isAdmin && one.getUserType() == PoetryEnum.USER_TYPE_ADMIN.getCode()) {
            userVO.setIsBoss(true);
        }

        userVO.setAccessToken(accessToken);
        return PoetryResult.success(userVO);
    }

    @Override
    public PoetryResult exit() {
        String token = PoetryUtil.getToken();
        Integer userId = PoetryUtil.getUserId();
        boolean userSession = token.startsWith(CommonConst.USER_ACCESS_TOKEN);
        synchronized (SESSION_LOCK) {
            String mappingKey = userSession
                    ? CommonConst.USER_TOKEN + userId : CommonConst.ADMIN_TOKEN + userId;
            Object mappedToken = PoetryCache.get(mappingKey);
            if (token.equals(mappedToken)) {
                PoetryCache.remove(mappingKey);
            }
            PoetryCache.remove(token);
        }
        if (userSession) {
            TioWebsocketStarter tioWebsocketStarter = TioUtil.getTio();
            if (tioWebsocketStarter != null) {
                Tio.removeUser(tioWebsocketStarter.getServerTioConfig(), String.valueOf(userId), "remove user");
            }
        }
        return PoetryResult.success();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PoetryResult<UserVO> regist(UserVO user) {
        user.setUsername(StringUtil.removeHtml(user.getUsername().trim()));
        if (StringUtils.hasText(user.getPhoneNumber())) {
            user.setPhoneNumber(user.getPhoneNumber().trim());
        }
        if (StringUtils.hasText(user.getEmail())) {
            user.setEmail(user.getEmail().trim());
        }
        String regex = "\\d{11}";
        if (user.getUsername().matches(regex)) {
            return PoetryResult.fail("用户名不能为11位数字！");
        }

        if (user.getUsername().contains("@")) {
            return PoetryResult.fail("用户名不能包含@！");
        }

        if (StringUtils.hasText(user.getPhoneNumber()) && StringUtils.hasText(user.getEmail())) {
            return PoetryResult.fail("手机号与邮箱只能选择其中一个！");
        }

        String verificationKey;
        if (StringUtils.hasText(user.getPhoneNumber())) {
            if (!isVerificationCode(user.getCode())) {
                return PoetryResult.fail("验证码格式错误！");
            }
            verificationKey = CommonConst.FORGET_PASSWORD + user.getPhoneNumber() + "_1";
            Integer codeCache = (Integer) PoetryCache.get(verificationKey);
            if (codeCache == null || codeCache != Integer.parseInt(user.getCode())) {
                return PoetryResult.fail("验证码错误！");
            }
        } else if (StringUtils.hasText(user.getEmail())) {
            if (!isVerificationCode(user.getCode())) {
                return PoetryResult.fail("验证码格式错误！");
            }
            verificationKey = CommonConst.FORGET_PASSWORD + user.getEmail() + "_2";
            Integer codeCache = (Integer) PoetryCache.get(verificationKey);
            if (codeCache == null || codeCache != Integer.parseInt(user.getCode())) {
                return PoetryResult.fail("验证码错误！");
            }
        } else {
            return PoetryResult.fail("请输入邮箱或手机号！");
        }


        user.setPassword(decryptPassword(user.getPassword()));
        validateNewPassword(user.getPassword());

        long count = lambdaQuery().eq(User::getUsername, user.getUsername()).count();
        if (count != 0) {
            return PoetryResult.fail("用户名重复！");
        }
        if (StringUtils.hasText(user.getPhoneNumber())) {
            long phoneNumberCount = lambdaQuery().eq(User::getPhoneNumber, user.getPhoneNumber()).count();
            if (phoneNumberCount != 0) {
                return PoetryResult.fail("手机号重复！");
            }
        } else if (StringUtils.hasText(user.getEmail())) {
            long emailCount = lambdaQuery().eq(User::getEmail, user.getEmail()).count();
            if (emailCount != 0) {
                return PoetryResult.fail("邮箱重复！");
            }
        }
        if (!PoetryCache.removeIfEquals(verificationKey, Integer.valueOf(user.getCode()))) {
            return PoetryResult.fail("验证码已失效或已被使用！");
        }

        try {
        User u = new User();
        u.setUsername(user.getUsername());
        u.setPhoneNumber(StringUtils.hasText(user.getPhoneNumber()) ? user.getPhoneNumber() : null);
        u.setEmail(StringUtils.hasText(user.getEmail()) ? user.getEmail() : null);
        u.setPassword(BCrypt.hashpw(user.getPassword()));
        u.setAvatar(PoetryUtil.getRandomAvatar(null));
        if (!save(u)) {
            throw new PoetryRuntimeException("用户注册失败！");
        }

        User one = lambdaQuery().eq(User::getId, u.getId()).one();
        if (one == null) {
            throw new PoetryRuntimeException("注册用户读取失败！");
        }

        WeiYan weiYan = new WeiYan();
        weiYan.setUserId(one.getId());
        weiYan.setContent("到此一游");
        weiYan.setType(CommonConst.WEIYAN_TYPE_FRIEND);
        weiYan.setIsPublic(Boolean.TRUE);
        if (!weiYanService.save(weiYan)) {
            throw new PoetryRuntimeException("注册欢迎信息保存失败！");
        }

        ImChatGroupUser imChatGroupUser = new ImChatGroupUser();
        imChatGroupUser.setGroupId(ImConfigConst.DEFAULT_GROUP_ID);
        imChatGroupUser.setUserId(one.getId());
        imChatGroupUser.setUserStatus(ImConfigConst.GROUP_USER_STATUS_PASS);
        if (imChatGroupUserMapper.insert(imChatGroupUser) != 1) {
            throw new PoetryRuntimeException("默认群关系保存失败！");
        }

        ImChatUserFriend imChatUser = new ImChatUserFriend();
        imChatUser.setUserId(one.getId());
        imChatUser.setFriendId(PoetryUtil.getAdminUser().getId());
        imChatUser.setRemark("站长");
        imChatUser.setFriendStatus(ImConfigConst.FRIEND_STATUS_PASS);
        if (imChatUserFriendMapper.insert(imChatUser) != 1) {
            throw new PoetryRuntimeException("默认好友关系保存失败！");
        }

        ImChatUserFriend imChatFriend = new ImChatUserFriend();
        imChatFriend.setUserId(PoetryUtil.getAdminUser().getId());
        imChatFriend.setFriendId(one.getId());
        imChatFriend.setFriendStatus(ImConfigConst.FRIEND_STATUS_PASS);
        if (imChatUserFriendMapper.insert(imChatFriend) != 1) {
            throw new PoetryRuntimeException("默认好友关系保存失败！");
        }

        String userToken = CommonConst.USER_ACCESS_TOKEN + UUID.randomUUID().toString().replaceAll("-", "");
        synchronized (SESSION_LOCK) {
            PoetryCache.put(userToken, one, CommonConst.TOKEN_EXPIRE);
            PoetryCache.put(CommonConst.USER_TOKEN + one.getId(), userToken, CommonConst.TOKEN_EXPIRE);
        }

        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(one, userVO);
        userVO.setPassword(null);
        userVO.setAccessToken(userToken);

        return PoetryResult.success(userVO);
        } catch (RuntimeException e) {
            PoetryCache.put(verificationKey, Integer.valueOf(user.getCode()), 300);
            throw e;
        }
    }

    @Override
    public PoetryResult<UserVO> updateUserInfo(UserVO user) {
        if (user == null || !StringUtils.hasText(user.getUsername())) {
            return PoetryResult.fail("用户名不能为空！");
        }
        String username = StringUtil.removeHtml(user.getUsername().trim());
        if (username.length() > 32) {
            return PoetryResult.fail("用户名不能超过 32 个字符！");
        }
        String regex = "\\d{11}";
        if (username.matches(regex)) {
            return PoetryResult.fail("用户名不能为11位数字！");
        }
        if (username.contains("@")) {
            return PoetryResult.fail("用户名不能包含@！");
        }
        if (StringUtils.hasText(user.getAvatar()) && user.getAvatar().length() > 256) {
            return PoetryResult.fail("头像地址不能超过 256 个字符！");
        }
        if (StringUtils.hasText(user.getIntroduction()) && user.getIntroduction().length() > 4096) {
            return PoetryResult.fail("个人简介不能超过 4096 个字符！");
        }
        if (user.getGender() != null && (user.getGender() < 0 || user.getGender() > 2)) {
            return PoetryResult.fail("性别参数不合法！");
        }
        long count = lambdaQuery().eq(User::getUsername, username).ne(User::getId, PoetryUtil.getUserId()).count();
        if (count != 0) {
            return PoetryResult.fail("用户名重复！");
        }
        User u = new User();
        u.setId(PoetryUtil.getUserId());
        u.setUsername(username);
        u.setAvatar(user.getAvatar());
        u.setGender(user.getGender());
        u.setIntroduction(user.getIntroduction());
        if (!updateById(u)) {
            return PoetryResult.fail("用户信息更新失败！");
        }
        User one = lambdaQuery().eq(User::getId, u.getId()).one();
        if (one == null) {
            return PoetryResult.fail("用户不存在！");
        }
        cacheCurrentSession(one);

        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(one, userVO);
        userVO.setPassword(null);
        userVO.setAccessToken(PoetryUtil.getToken());
        return PoetryResult.success(userVO);
    }

    @Override
    public PoetryResult getCode(Integer flag) {
        if (flag == null || (flag != 1 && flag != 2)) {
            return PoetryResult.fail("验证码类型不合法！");
        }
        User user = PoetryUtil.getCurrentUser();
        int i = generateVerificationCode();
        if (flag == 1) {
            if (!StringUtils.hasText(user.getPhoneNumber())) {
                return PoetryResult.fail("请先绑定手机号！");
            }
            return PoetryResult.fail("暂不支持短信验证码！");
        } else if (flag == 2) {
            if (!StringUtils.hasText(user.getEmail())) {
                return PoetryResult.fail("请先绑定邮箱！");
            }

            List<String> mail = new ArrayList<>();
            mail.add(user.getEmail());
            String text = getCodeMail(i);
            WebInfo webInfo = (WebInfo) PoetryCache.get(CommonConst.WEB_INFO);

            int count = PoetryCache.increment(CommonConst.CODE_MAIL + mail.get(0), CommonConst.CODE_EXPIRE);
            if (count <= CommonConst.CODE_MAIL_COUNT) {
                mailUtil.sendMailMessage(mail, "您有一封来自" + (webInfo == null ? "POETIZE" : webInfo.getWebName()) + "的回执！", text);
            } else {
                return PoetryResult.fail("验证码发送次数过多，请明天再试！");
            }
        }
        PoetryCache.put(CommonConst.USER_CODE + PoetryUtil.getUserId() + "_" + flag, Integer.valueOf(i), 300);
        return PoetryResult.success();
    }

    @Override
    public PoetryResult getCodeForBind(String place, Integer flag) {
        if (flag == null || (flag != 1 && flag != 2)) {
            return PoetryResult.fail("验证码类型不合法！");
        }
        if (!StringUtils.hasText(place)) {
            return PoetryResult.fail("手机号或邮箱不能为空！");
        }
        place = normalizeContact(place, flag);
        int i = generateVerificationCode();
        if (flag == 1) {
            return PoetryResult.fail("暂不支持短信验证码！");
        } else if (flag == 2) {
            List<String> mail = new ArrayList<>();
            mail.add(place);
            String text = getCodeMail(i);
            WebInfo webInfo = (WebInfo) PoetryCache.get(CommonConst.WEB_INFO);

            int count = PoetryCache.increment(CommonConst.CODE_MAIL + mail.get(0), CommonConst.CODE_EXPIRE);
            if (count <= CommonConst.CODE_MAIL_COUNT) {
                mailUtil.sendMailMessage(mail, "您有一封来自" + (webInfo == null ? "POETIZE" : webInfo.getWebName()) + "的回执！", text);
            } else {
                return PoetryResult.fail("验证码发送次数过多，请明天再试！");
            }
        }
        PoetryCache.put(CommonConst.USER_CODE + PoetryUtil.getUserId() + "_" + place + "_" + flag, Integer.valueOf(i), 300);
        return PoetryResult.success();
    }

    @Override
    public PoetryResult<UserVO> updateSecretInfo(String place, Integer flag, String code, String password) {
        if (flag == null || (flag != 1 && flag != 2 && flag != 3)) {
            return PoetryResult.fail("操作类型不合法！");
        }
        if ((flag == 1 || flag == 2) && !isVerificationCode(code)) {
            return PoetryResult.fail("验证码格式错误！");
        }
        if (!StringUtils.hasText(place)) {
            return PoetryResult.fail("参数不能为空！");
        }
        if (flag == 1 || flag == 2) {
            place = normalizeContact(place, flag);
        }
        password = decryptPassword(password);
        if (flag == 3) {
            validateNewPassword(password);
        }

        User user = PoetryUtil.getCurrentUser();
        if ((flag == 1 || flag == 2) && !passwordMatches(password, user.getPassword())) {
            return PoetryResult.fail("密码错误！");
        }
        User updateUser = new User();
        updateUser.setId(user.getId());
        String verificationKey = null;
        if (flag == 1) {
            long count = lambdaQuery().eq(User::getPhoneNumber, place).count();
            if (count != 0) {
                return PoetryResult.fail("手机号重复！");
            }
            verificationKey = CommonConst.USER_CODE + PoetryUtil.getUserId() + "_" + place + "_" + flag;
            Integer codeCache = (Integer) PoetryCache.get(verificationKey);
            if (codeCache != null && codeCache.intValue() == Integer.parseInt(code)) {
                updateUser.setPhoneNumber(place);
            } else {
                return PoetryResult.fail("验证码错误！");
            }

        } else if (flag == 2) {
            long count = lambdaQuery().eq(User::getEmail, place).count();
            if (count != 0) {
                return PoetryResult.fail("邮箱重复！");
            }
            verificationKey = CommonConst.USER_CODE + PoetryUtil.getUserId() + "_" + place + "_" + flag;
            Integer codeCache = (Integer) PoetryCache.get(verificationKey);
            if (codeCache != null && codeCache.intValue() == Integer.parseInt(code)) {
                updateUser.setEmail(place);
            } else {
                return PoetryResult.fail("验证码错误！");
            }
        } else if (flag == 3) {
            if (passwordMatches(place, user.getPassword())) {
                updateUser.setPassword(BCrypt.hashpw(password));
            } else {
                return PoetryResult.fail("密码错误！");
            }
        }
        boolean updated;
        synchronized (SESSION_LOCK) {
            if (verificationKey != null
                    && !PoetryCache.removeIfEquals(verificationKey, Integer.valueOf(code))) {
                return PoetryResult.fail("验证码已失效或已被使用！");
            }
            try {
                if (flag == 3) {
                    updated = updateById(updateUser);
                    if (updated) {
                        revokeUserSessions(user.getId());
                    }
                } else {
                    updated = updateById(updateUser);
                    if (!updated && verificationKey != null) {
                        PoetryCache.put(verificationKey, Integer.valueOf(code), 300);
                    }
                }
            } catch (RuntimeException e) {
                if (verificationKey != null) {
                    PoetryCache.put(verificationKey, Integer.valueOf(code), 300);
                }
                throw e;
            }
        }
        if (!updated) {
            return PoetryResult.fail("账号信息更新失败！");
        }

        User one = lambdaQuery().eq(User::getId, user.getId()).one();
        if (one == null) {
            return PoetryResult.fail("用户不存在！");
        }
        if (flag != 3) {
            cacheCurrentSession(one);
        }

        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(one, userVO);
        userVO.setPassword(null);
        return PoetryResult.success(userVO);
    }

    @Override
    public PoetryResult getCodeForForgetPassword(String place, Integer flag) {
        if (flag == null || (flag != 1 && flag != 2)) {
            return PoetryResult.fail("验证码类型不合法！");
        }
        if (!StringUtils.hasText(place)) {
            return PoetryResult.fail("手机号或邮箱不能为空！");
        }
        place = normalizeContact(place, flag);
        int i = generateVerificationCode();
        if (flag == 1) {
            return PoetryResult.fail("暂不支持短信验证码！");
        } else if (flag == 2) {

            List<String> mail = new ArrayList<>();
            mail.add(place);
            String text = getCodeMail(i);
            WebInfo webInfo = (WebInfo) PoetryCache.get(CommonConst.WEB_INFO);

            int count = PoetryCache.increment(CommonConst.CODE_MAIL + mail.get(0), CommonConst.CODE_EXPIRE);
            if (count <= CommonConst.CODE_MAIL_COUNT) {
                mailUtil.sendMailMessage(mail, "您有一封来自" + (webInfo == null ? "POETIZE" : webInfo.getWebName()) + "的回执！", text);
            } else {
                return PoetryResult.fail("验证码发送次数过多，请明天再试！");
            }
        }
        PoetryCache.put(CommonConst.FORGET_PASSWORD + place + "_" + flag, Integer.valueOf(i), 300);
        return PoetryResult.success();
    }

    @Override
    public PoetryResult updateForForgetPassword(String place, Integer flag, String code, String password) {
        if (flag == null || (flag != 1 && flag != 2)) {
            return PoetryResult.fail("操作类型不合法！");
        }
        if (!StringUtils.hasText(place) || !isVerificationCode(code)) {
            return PoetryResult.fail("参数或验证码格式错误！");
        }
        place = normalizeContact(place, flag);
        password = decryptPassword(password);
        validateNewPassword(password);

        String verificationKey = CommonConst.FORGET_PASSWORD + place + "_" + flag;
        Integer codeCache = (Integer) PoetryCache.get(verificationKey);
        if (codeCache == null || codeCache != Integer.parseInt(code)) {
            return PoetryResult.fail("验证码错误！");
        }
        User user = flag == 1
                ? lambdaQuery().eq(User::getPhoneNumber, place).one()
                : lambdaQuery().eq(User::getEmail, place).one();
        if (user == null) {
            return PoetryResult.fail(flag == 1 ? "该手机号未绑定账号！" : "该邮箱未绑定账号！");
        }
        if (!Boolean.TRUE.equals(user.getUserStatus())) {
            return PoetryResult.fail("账号被冻结！");
        }
        synchronized (SESSION_LOCK) {
            if (!PoetryCache.removeIfEquals(verificationKey, Integer.valueOf(code))) {
                return PoetryResult.fail("验证码已失效或已被使用！");
            }
            try {
                boolean updated = lambdaUpdate().eq(User::getId, user.getId())
                        .set(User::getPassword, BCrypt.hashpw(password)).update();
                if (!updated) {
                    PoetryCache.put(verificationKey, Integer.valueOf(code), 300);
                    return PoetryResult.fail("密码重置失败！");
                }
                revokeUserSessions(user.getId());
            } catch (RuntimeException e) {
                PoetryCache.put(verificationKey, Integer.valueOf(code), 300);
                throw e;
            }
        }

        return PoetryResult.success();
    }

    @Override
    public PoetryResult<Page> listUser(BaseRequestVO baseRequestVO) {
        LambdaQueryChainWrapper<User> lambdaQuery = lambdaQuery();

        if (baseRequestVO.getUserStatus() != null) {
            lambdaQuery.eq(User::getUserStatus, baseRequestVO.getUserStatus());
        }

        if (baseRequestVO.getUserType() != null) {
            lambdaQuery.eq(User::getUserType, baseRequestVO.getUserType());
        }

        if (StringUtils.hasText(baseRequestVO.getSearchKey())) {
            lambdaQuery.and(lq -> lq.like(User::getUsername, baseRequestVO.getSearchKey())
                    .or()
                    .like(User::getPhoneNumber, baseRequestVO.getSearchKey())
                    .or()
                    .like(User::getEmail, baseRequestVO.getSearchKey()));
        }

        lambdaQuery.orderByDesc(User::getCreateTime).page(baseRequestVO);

        List<User> records = baseRequestVO.getRecords();
        if (!CollectionUtils.isEmpty(records)) {
            records.forEach(u -> {
                u.setPassword(null);
                u.setOpenId(null);
            });
        }
        return PoetryResult.success(baseRequestVO);
    }

    @Override
    public PoetryResult<List<UserVO>> getUserByUsername(String username) {
        List<User> users = lambdaQuery().select(User::getId, User::getUsername, User::getAvatar, User::getGender, User::getIntroduction).like(User::getUsername, username).last("limit 5").list();
        List<UserVO> userVOS = users.stream().map(u -> {
            UserVO userVO = new UserVO();
            userVO.setId(u.getId());
            userVO.setUsername(u.getUsername());
            userVO.setAvatar(u.getAvatar());
            userVO.setIntroduction(u.getIntroduction());
            userVO.setGender(u.getGender());
            return userVO;
        }).collect(Collectors.toList());
        return PoetryResult.success(userVOS);
    }

    @Override
    public PoetryResult<UserVO> token(String userToken) {
        userToken = decryptToken(userToken);

        if (!StringUtils.hasText(userToken)) {
            throw new PoetryRuntimeException("未登陆，请登陆后再进行操作！");
        }

        User user = (User) PoetryCache.get(userToken);

        if (user == null) {
            throw new PoetryRuntimeException("登录已过期，请重新登陆！");
        }

        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        userVO.setPassword(null);

        userVO.setAccessToken(userToken);

        return PoetryResult.success(userVO);
    }

    @Override
    public PoetryResult<UserVO> subscribe(Integer labelId, Boolean flag) {
        if (labelId == null || flag == null) {
            return PoetryResult.fail("订阅参数不合法！");
        }
        long labelCount = new LambdaQueryChainWrapper<>(labelMapper)
                .eq(Label::getId, labelId)
                .count();
        if (labelCount != 1) {
            return PoetryResult.fail("标签不存在！");
        }
        User one = null;
        boolean completed = false;
        for (int attempt = 0; attempt < 3 && !completed; attempt++) {
            one = lambdaQuery().eq(User::getId, PoetryUtil.getUserId()).one();
            if (one == null) {
                return PoetryResult.fail("用户不存在！");
            }
            String originalSubscribe = one.getSubscribe();
            List<Integer> sub;
            try {
                sub = JSON.parseArray(originalSubscribe, Integer.class);
            } catch (RuntimeException ignored) {
                sub = null;
            }
            if (sub == null) {
                sub = new ArrayList<>();
            }
            boolean changed = flag ? !sub.contains(labelId) : sub.contains(labelId);
            if (!changed) {
                completed = true;
                continue;
            }
            if (flag) {
                sub.add(labelId);
            } else {
                sub.remove(labelId);
            }
            String subscribe = JSON.toJSONString(sub);
            var update = lambdaUpdate().eq(User::getId, one.getId());
            if (originalSubscribe == null) {
                update.isNull(User::getSubscribe);
            } else {
                update.eq(User::getSubscribe, originalSubscribe);
            }
            if (update.set(User::getSubscribe, subscribe).update()) {
                one.setSubscribe(subscribe);
                completed = true;
            }
        }
        if (!completed || one == null) {
            return PoetryResult.fail("订阅状态更新冲突，请重试！");
        }
        PoetryCache.remove(CommonConst.USER_CACHE + one.getId());
        cacheCurrentSession(one);
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(one, userVO);
        userVO.setPassword(null);
        userVO.setAccessToken(PoetryUtil.getToken());
        return PoetryResult.success(userVO);
    }

    private void cacheCurrentSession(User user) {
        synchronized (SESSION_LOCK) {
            String token = PoetryUtil.getToken();
            PoetryCache.put(token, user, CommonConst.TOKEN_EXPIRE);
            if (token.startsWith(CommonConst.ADMIN_ACCESS_TOKEN)) {
                PoetryCache.put(CommonConst.ADMIN_TOKEN + user.getId(), token, CommonConst.TOKEN_EXPIRE);
            } else {
                PoetryCache.put(CommonConst.USER_TOKEN + user.getId(), token, CommonConst.TOKEN_EXPIRE);
            }
        }
    }

    private void revokeUserSessions(Integer userId) {
        synchronized (SESSION_LOCK) {
            String suffix = userId.toString();
            revokeMappedToken(CommonConst.USER_TOKEN + suffix);
            revokeMappedToken(CommonConst.ADMIN_TOKEN + suffix);
            PoetryCache.remove(CommonConst.USER_TOKEN_INTERVAL + suffix);
            PoetryCache.remove(CommonConst.ADMIN_TOKEN_INTERVAL + suffix);
            PoetryCache.remove(CommonConst.USER_CACHE + suffix);
        }
    }

    private void revokeMappedToken(String mappingKey) {
        Object mappedToken = PoetryCache.remove(mappingKey);
        if (mappedToken instanceof String token && StringUtils.hasText(token)) {
            PoetryCache.remove(token);
        }
    }

    /** SESSION_LOCK 必须由调用方持有，保证映射键与令牌键成对创建。 */
    private String getOrCreateSessionTokenLocked(User user, boolean admin) {
        String mappingKey = (admin ? CommonConst.ADMIN_TOKEN : CommonConst.USER_TOKEN) + user.getId();
        Object mappedToken = PoetryCache.get(mappingKey);
        if (mappedToken instanceof String token && PoetryCache.get(token) instanceof User) {
            PoetryCache.put(token, user, CommonConst.TOKEN_EXPIRE);
            PoetryCache.put(mappingKey, token, CommonConst.TOKEN_EXPIRE);
            return token;
        }
        if (mappedToken instanceof String staleToken) {
            PoetryCache.remove(staleToken);
        }
        PoetryCache.remove(mappingKey);
        String prefix = admin ? CommonConst.ADMIN_ACCESS_TOKEN : CommonConst.USER_ACCESS_TOKEN;
        String token = prefix + UUID.randomUUID().toString().replace("-", "");
        PoetryCache.put(token, user, CommonConst.TOKEN_EXPIRE);
        PoetryCache.put(mappingKey, token, CommonConst.TOKEN_EXPIRE);
        return token;
    }

    private int generateVerificationCode() {
        return SECURE_RANDOM.nextInt(900000) + 100000;
    }

    private boolean isVerificationCode(String code) {
        return code != null && code.matches("\\d{6}");
    }

    private String normalizeContact(String place, Integer flag) {
        String normalized = place == null ? "" : place.trim();
        if (flag == 1) {
            if (!normalized.matches("\\d{11}")) {
                throw new PoetryRuntimeException("手机号格式不正确！");
            }
            return normalized;
        }
        if (normalized.length() > 254 || !normalized.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new PoetryRuntimeException("邮箱格式不正确！");
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private void validateNewPassword(String password) {
        boolean hasLetter = password.codePoints().anyMatch(Character::isLetter);
        boolean hasDigit = password.codePoints().anyMatch(Character::isDigit);
        if (password.length() < 8 || !hasLetter || !hasDigit || !isBcryptCompatible(password)) {
            throw new PoetryRuntimeException("密码至少 8 位且必须包含字母和数字，UTF-8 编码后不能超过 72 字节！");
        }
    }

    private boolean isBcryptCompatible(String password) {
        return password != null && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    private String decryptToken(String encryptedToken) {
        if (!StringUtils.hasText(encryptedToken) || encryptedToken.length() > 512) {
            throw new PoetryRuntimeException("登录凭证不能为空！");
        }
        try {
            String token = new String(
                    SecureUtil.aes(CommonConst.CRYPOTJS_KEY.getBytes(StandardCharsets.UTF_8)).decrypt(encryptedToken),
                    StandardCharsets.UTF_8);
            if (!StringUtils.hasText(token)) {
                throw new PoetryRuntimeException("登录凭证无效！");
            }
            return token;
        } catch (PoetryRuntimeException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PoetryRuntimeException("登录凭证格式不正确！");
        }
    }

    private String decryptPassword(String encryptedPassword) {
        if (!StringUtils.hasText(encryptedPassword) || encryptedPassword.length() > 512) {
            throw new PoetryRuntimeException("密码不能为空！");
        }
        try {
            byte[] decrypted = SecureUtil.aes(CommonConst.CRYPOTJS_KEY.getBytes(StandardCharsets.UTF_8))
                    .decrypt(encryptedPassword);
            String password = new String(decrypted, StandardCharsets.UTF_8);
            if (!StringUtils.hasText(password)) {
                throw new PoetryRuntimeException("密码不能为空！");
            }
            return password;
        } catch (PoetryRuntimeException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PoetryRuntimeException("密码格式不正确！");
        }
    }

    private boolean passwordMatches(String plainPassword, String storedPassword) {
        if (!StringUtils.hasText(plainPassword) || !StringUtils.hasText(storedPassword)) {
            return false;
        }
        if (isLegacyMd5(storedPassword)) {
            String md5 = DigestUtils.md5DigestAsHex(plainPassword.getBytes(StandardCharsets.UTF_8));
            return md5.equalsIgnoreCase(storedPassword);
        }
        try {
            return BCrypt.checkpw(plainPassword, storedPassword);
        } catch (IllegalArgumentException e) {
            log.warn("用户密码散列格式无效");
            return false;
        }
    }

    private boolean isLegacyMd5(String storedPassword) {
        return storedPassword != null && storedPassword.matches("(?i)^[0-9a-f]{32}$");
    }

    private String getCodeMail(int i) {
        WebInfo webInfo = (WebInfo) PoetryCache.get(CommonConst.WEB_INFO);
        String webName = (webInfo == null ? "POETIZE" : webInfo.getWebName());
        return String.format(mailUtil.getMailText(),
                webName,
                String.format(MailUtil.imMail, PoetryUtil.getAdminUser().getUsername()),
                PoetryUtil.getAdminUser().getUsername(),
                String.format(codeFormat, i),
                "",
                webName);
    }
}
