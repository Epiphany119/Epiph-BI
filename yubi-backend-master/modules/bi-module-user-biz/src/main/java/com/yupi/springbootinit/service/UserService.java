package com.yupi.springbootinit.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.IService;
import com.yupi.springbootinit.model.dto.user.UserQueryRequest;
import com.yupi.springbootinit.model.entity.User;
import com.yupi.springbootinit.model.vo.LoginUserVO;
import com.yupi.springbootinit.model.vo.UserVO;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import me.chanjar.weixin.common.bean.WxOAuth2UserInfo;

/**
 * 用户服务
 *
 * @author <a href="https://github.com/liyupi">程序员鱼皮</a>
 * @from <a href="https://yupi.icu">编程导航知识星球</a>
 */
public interface UserService extends IService<User> {

    /**
     * 用户注册
     *
     * @param userAccount   用户账户
     * @param userPassword  用户密码
     * @param checkPassword 校验密码
     * @return 新用户 id
     */
    long userRegister(String userAccount, String userPassword, String checkPassword);

    /**
     * 用户注册（支持同时绑定邮箱用于找回密码）
     *
     * @param userAccount   用户账户
     * @param userPassword  用户密码
     * @param checkPassword 校验密码
     * @param email         绑定邮箱
     * @return 新用户 id
     */
    long userRegister(String userAccount, String userPassword, String checkPassword, String email);

    /**
     * 用户登录
     *
     * @param userAccount  用户账户
     * @param userPassword 用户密码
     * @param request
     * @return 脱敏后的用户信息
     */
    LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request);

    /**
     * 校验账号与绑定邮箱并发送 6 位 QQ SMTP 重置验证码（5 分钟有效，60 秒冷却）。
     *
     * @param userAccount 用户账号
     * @param email       用户输入的绑定邮箱
     * @return 脱敏后的收件邮箱（例如 12***6@qq.com）
     */
    String requestPasswordResetCode(String userAccount, String email);

    /**
     * 校验一次性验证码并重置用户密码。
     *
     * @param userAccount   用户账号
     * @param resetCode     6 位重置验证码
     * @param newPassword   新密码（至少 8 位）
     * @param checkPassword 确认新密码
     * @return 是否重置成功
     */
    boolean resetPassword(String userAccount, String resetCode, String newPassword, String checkPassword);

    /**
     * 向待绑定邮箱发送 6 位一次性验证码（5 分钟有效，60 秒冷却）。
     *
     * @param userId 当前登录用户 ID
     * @param email  待绑定邮箱
     * @return 脱敏后的目标邮箱
     */
    String requestBindEmailCode(Long userId, String email);

    /**
     * 校验一次性验证码后确认绑定邮箱（可选同步更新昵称）。
     *
     * @param userId     当前登录用户 ID
     * @param email      待绑定邮箱
     * @param verifyCode 6 位邮箱验证码
     * @param userName   可选新昵称
     * @return 是否绑定成功
     */
    boolean confirmBindEmail(Long userId, String email, String verifyCode, String userName);

    /**
     * 向已绑定邮箱发送 6 位登录验证码。
     *
     * @param email 已绑定邮箱
     * @return 脱敏后的收件邮箱
     */
    String requestEmailLoginCode(String email);

    /**
     * 通过邮箱验证码校验身份并完成登录。
     *
     * @param email   已绑定邮箱
     * @param code    6 位邮箱验证码
     * @param request HTTP 请求（用于写入登录会话）
     * @return 脱敏后的登录用户信息
     */
    LoginUserVO userLoginByEmail(String email, String code, HttpServletRequest request);

    /**
     * 获取当前登录用户
     *
     * @param request
     * @return
     */
    User getLoginUser(HttpServletRequest request);

    /**
     * 获取当前登录用户（允许未登录）
     *
     * @param request
     * @return
     */
    User getLoginUserPermitNull(HttpServletRequest request);

    /**
     * 是否为管理员
     *
     * @param request
     * @return
     */
    boolean isAdmin(HttpServletRequest request);

    /**
     * 是否为管理员
     *
     * @param user
     * @return
     */
    boolean isAdmin(User user);

    /**
     * 用户注销
     *
     * @param request
     * @return
     */
    boolean userLogout(HttpServletRequest request);

    /**
     * 获取脱敏的已登录用户信息
     *
     * @return
     */
    LoginUserVO getLoginUserVO(User user);

    /**
     * 获取脱敏的用户信息
     *
     * @param user
     * @return
     */
    UserVO getUserVO(User user);

    /**
     * 获取脱敏的用户信息
     *
     * @param userList
     * @return
     */
    List<UserVO> getUserVO(List<User> userList);

    /**
     * 获取查询条件
     *
     * @param userQueryRequest
     * @return
     */
    QueryWrapper<User> getQueryWrapper(UserQueryRequest userQueryRequest);

}
