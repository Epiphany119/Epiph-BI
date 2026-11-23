package com.yupi.springbootinit.service.impl;

import static com.yupi.springbootinit.constant.UserConstant.USER_LOGIN_STATE;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.constant.CommonConstant;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.mapper.UserMapper;
import com.yupi.springbootinit.model.dto.user.UserQueryRequest;
import com.yupi.springbootinit.model.entity.User;
import com.yupi.springbootinit.model.enums.UserRoleEnum;
import com.yupi.springbootinit.model.vo.LoginUserVO;
import com.yupi.springbootinit.model.vo.UserVO;
import com.yupi.springbootinit.service.PasswordResetMailService;
import com.yupi.springbootinit.service.PasswordResetTokenService;
import com.yupi.springbootinit.service.UserService;
import com.yupi.springbootinit.utils.SqlUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

/**
 * 用户服务实现
 *
 * @author <a href="https://github.com/liyupi">程序员鱼皮</a>
 * @from <a href="https://yupi.icu">编程导航知识星球</a>
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    /**
     * 盐值，混淆密码
     */
    private static final String SALT = "yupi";

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    @Autowired
    private PasswordResetTokenService passwordResetTokenService;

    @Autowired
    private PasswordResetMailService passwordResetMailService;

    @Override
    public long userRegister(String userAccount, String userPassword, String checkPassword) {
        return userRegister(userAccount, userPassword, checkPassword, null);
    }

    @Override
    public long userRegister(String userAccount, String userPassword, String checkPassword, String email) {
        // 1. 校验
        if (StringUtils.isAnyBlank(userAccount, userPassword, checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (userAccount.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户账号过短");
        }
        if (userPassword.length() < 8 || checkPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户密码过短");
        }
        // 密码和校验密码相同
        if (!userPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        }
        String normalizedEmail = StringUtils.isBlank(email) ? null : email.trim();
        if (normalizedEmail != null && !EMAIL_PATTERN.matcher(normalizedEmail).matches()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "邮箱格式不正确");
        }
        synchronized (userAccount.intern()) {
            // 账户不能重复
            QueryWrapper<User> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("userAccount", userAccount);
            long count = this.baseMapper.selectCount(queryWrapper);
            if (count > 0) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号重复");
            }
            // 2. 加密
            String encryptPassword = DigestUtils.md5DigestAsHex((SALT + userPassword).getBytes());
            // 3. 插入数据
            User user = new User();
            user.setUserAccount(userAccount);
            user.setUserPassword(encryptPassword);
            user.setEmail(normalizedEmail);
            boolean saveResult = this.save(user);
            if (!saveResult) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "注册失败，数据库错误");
            }
            return user.getId();
        }
    }

    @Override
    public LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request) {
        // 1. 校验
        if (StringUtils.isAnyBlank(userAccount, userPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (userAccount.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号错误");
        }
        if (userPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码错误");
        }
        // 2. 加密
        String encryptPassword = DigestUtils.md5DigestAsHex((SALT + userPassword).getBytes());
        // 查询用户是否存在
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount", userAccount);
        queryWrapper.eq("userPassword", encryptPassword);
        User user = this.baseMapper.selectOne(queryWrapper);
        // 用户不存在
        if (user == null) {
            log.info("user login failed, userAccount cannot match userPassword");
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在或密码错误");
        }
        // 3. 记录用户的登录态
        request.getSession().setAttribute(USER_LOGIN_STATE, user);
        return this.getLoginUserVO(user);
    }

    @Override
    public String requestPasswordResetCode(String userAccount, String email) {
        if (StringUtils.isBlank(userAccount) || userAccount.trim().length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请输入有效的用户账号（至少 4 位）");
        }
        if (StringUtils.isBlank(email) || !EMAIL_PATTERN.matcher(email.trim()).matches()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请输入有效的绑定邮箱");
        }
        String normalizedAccount = userAccount.trim();
        String normalizedEmail = email.trim();
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount", normalizedAccount);
        User user = this.baseMapper.selectOne(queryWrapper);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "该账号不存在");
        }
        if (StringUtils.isBlank(user.getEmail())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "该账号尚未绑定邮箱，无法通过邮件找回密码");
        }
        if (!user.getEmail().trim().equalsIgnoreCase(normalizedEmail)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "输入的邮箱与该账号绑定邮箱不一致");
        }
        String code = passwordResetTokenService.issueCode(normalizedAccount);
        boolean sentByMail;
        try {
            sentByMail = passwordResetMailService.sendResetCode(user.getEmail(), normalizedAccount, code);
        } catch (RuntimeException e) {
            passwordResetTokenService.invalidate(normalizedAccount);
            throw e;
        }
        return sentByMail ? passwordResetMailService.maskEmail(user.getEmail()) : "DEV_FALLBACK:" + code;
    }

    @Override
    public boolean resetPassword(String userAccount, String resetCode, String newPassword, String checkPassword) {
        if (StringUtils.isAnyBlank(userAccount, resetCode, newPassword, checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请完整填写账号、验证码和新密码");
        }
        String normalizedAccount = userAccount.trim();
        if (normalizedAccount.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户账号过短");
        }
        if (newPassword.length() < 8 || checkPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "新密码长度不能少于 8 位");
        }
        if (!newPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的新密码不一致");
        }
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount", normalizedAccount);
        User user = this.baseMapper.selectOne(queryWrapper);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "该账号不存在");
        }
        passwordResetTokenService.verifyAndConsumeStrict(normalizedAccount, resetCode);
        User update = new User();
        update.setId(user.getId());
        update.setUserPassword(DigestUtils.md5DigestAsHex((SALT + newPassword).getBytes()));
        boolean updated = this.updateById(update);
        if (!updated) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "密码重置失败，请稍后重试");
        }
        log.info("[PasswordReset] password updated for userAccount={}", normalizedAccount);
        return true;
    }

    @Override
    public String requestBindEmailCode(Long userId, String email) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        if (StringUtils.isBlank(email) || !EMAIL_PATTERN.matcher(email.trim()).matches()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请输入有效的邮箱地址");
        }
        String normalizedEmail = email.trim();
        User currentUser = this.getById(userId);
        if (currentUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        QueryWrapper<User> dupQuery = new QueryWrapper<>();
        dupQuery.eq("email", normalizedEmail).ne("id", userId);
        if (this.baseMapper.selectCount(dupQuery) > 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该邮箱已被其他账号绑定");
        }
        String tokenKey = bindEmailTokenKey(userId, normalizedEmail);
        String code = passwordResetTokenService.issueCode(tokenKey);
        boolean sentByMail;
        try {
            sentByMail = passwordResetMailService.sendBindEmailCode(normalizedEmail, currentUser.getUserAccount(), code);
        } catch (RuntimeException e) {
            passwordResetTokenService.invalidate(tokenKey);
            throw e;
        }
        return sentByMail ? passwordResetMailService.maskEmail(normalizedEmail) : "DEV_FALLBACK:" + code;
    }

    @Override
    public boolean confirmBindEmail(Long userId, String email, String verifyCode, String userName) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        if (StringUtils.isAnyBlank(email, verifyCode)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请填写邮箱和 6 位验证码");
        }
        String normalizedEmail = email.trim();
        if (!EMAIL_PATTERN.matcher(normalizedEmail).matches()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "邮箱格式不正确");
        }
        QueryWrapper<User> dupQuery = new QueryWrapper<>();
        dupQuery.eq("email", normalizedEmail).ne("id", userId);
        if (this.baseMapper.selectCount(dupQuery) > 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该邮箱已被其他账号绑定");
        }
        String tokenKey = bindEmailTokenKey(userId, normalizedEmail);
        passwordResetTokenService.verifyAndConsumeStrict(tokenKey, verifyCode);

        User update = new User();
        update.setId(userId);
        update.setEmail(normalizedEmail);
        if (StringUtils.isNotBlank(userName)) {
            update.setUserName(userName.trim());
        }
        boolean updated = this.updateById(update);
        if (!updated) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "邮箱绑定失败，请稍后重试");
        }
        log.info("[BindEmail] bound email {} for userId={}", passwordResetMailService.maskEmail(normalizedEmail), userId);
        return true;
    }

    private String bindEmailTokenKey(Long userId, String normalizedEmail) {
        return "bind:" + userId + ":" + normalizedEmail.toLowerCase();
    }

    /**
     * 获取当前登录用户
     *
     * @param request
     * @return
     */
    @Override
    public User getLoginUser(HttpServletRequest request) {
        // 先判断是否已登录
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        User currentUser = (User) userObj;
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        // 从数据库查询（追求性能的话可以注释，直接走缓存）
        long userId = currentUser.getId();
        currentUser = this.getById(userId);
        if (currentUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return currentUser;
    }

    /**
     * 获取当前登录用户（允许未登录）
     *
     * @param request
     * @return
     */
    @Override
    public User getLoginUserPermitNull(HttpServletRequest request) {
        // 先判断是否已登录
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        User currentUser = (User) userObj;
        if (currentUser == null || currentUser.getId() == null) {
            return null;
        }
        // 从数据库查询（追求性能的话可以注释，直接走缓存）
        long userId = currentUser.getId();
        return this.getById(userId);
    }

    /**
     * 是否为管理员
     *
     * @param request
     * @return
     */
    @Override
    public boolean isAdmin(HttpServletRequest request) {
        // 仅管理员可查询
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        User user = (User) userObj;
        return isAdmin(user);
    }

    @Override
    public boolean isAdmin(User user) {
        return user != null && UserRoleEnum.ADMIN.getValue().equals(user.getUserRole());
    }

    /**
     * 用户注销
     *
     * @param request
     */
    @Override
    public boolean userLogout(HttpServletRequest request) {
        if (request.getSession().getAttribute(USER_LOGIN_STATE) == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "未登录");
        }
        // 移除登录态
        request.getSession().removeAttribute(USER_LOGIN_STATE);
        return true;
    }

    @Override
    public LoginUserVO getLoginUserVO(User user) {
        if (user == null) {
            return null;
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        BeanUtils.copyProperties(user, loginUserVO);
        return loginUserVO;
    }

    @Override
    public UserVO getUserVO(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        return userVO;
    }

    @Override
    public List<UserVO> getUserVO(List<User> userList) {
        if (CollectionUtils.isEmpty(userList)) {
            return new ArrayList<>();
        }
        return userList.stream().map(this::getUserVO).collect(Collectors.toList());
    }

    @Override
    public QueryWrapper<User> getQueryWrapper(UserQueryRequest userQueryRequest) {
        if (userQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = userQueryRequest.getId();
        String unionId = userQueryRequest.getUnionId();
        String mpOpenId = userQueryRequest.getMpOpenId();
        String userName = userQueryRequest.getUserName();
        String userProfile = userQueryRequest.getUserProfile();
        String userRole = userQueryRequest.getUserRole();
        String sortField = userQueryRequest.getSortField();
        String sortOrder = userQueryRequest.getSortOrder();
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(id != null, "id", id);
        queryWrapper.eq(StringUtils.isNotBlank(unionId), "unionId", unionId);
        queryWrapper.eq(StringUtils.isNotBlank(mpOpenId), "mpOpenId", mpOpenId);
        queryWrapper.eq(StringUtils.isNotBlank(userRole), "userRole", userRole);
        queryWrapper.like(StringUtils.isNotBlank(userProfile), "userProfile", userProfile);
        queryWrapper.like(StringUtils.isNotBlank(userName), "userName", userName);
        queryWrapper.orderBy(SqlUtils.validSortField(sortField), sortOrder.equals(CommonConstant.SORT_ORDER_ASC),
                sortField);
        return queryWrapper;
    }
}
