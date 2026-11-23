package com.yupi.springbootinit.model.dto.user;

import lombok.Data;

import java.io.Serializable;

/** 邮箱验证码登录请求。 */
@Data
public class UserEmailLoginRequest implements Serializable {
    private String email;
    private String code;

    private static final long serialVersionUID = 1L;
}
