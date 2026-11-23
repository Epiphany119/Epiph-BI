package com.yupi.springbootinit.model.dto.user;

import lombok.Data;

import java.io.Serializable;

/** 获取密码重置验证码请求（校验账号与绑定邮箱后发送 QQ SMTP 邮件）。 */
@Data
public class UserResetCodeRequest implements Serializable {
    private String userAccount;
    private String email;

    private static final long serialVersionUID = 1L;
}
