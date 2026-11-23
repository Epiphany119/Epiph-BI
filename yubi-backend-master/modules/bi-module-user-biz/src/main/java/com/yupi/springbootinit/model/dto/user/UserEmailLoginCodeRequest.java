package com.yupi.springbootinit.model.dto.user;

import lombok.Data;

import java.io.Serializable;

/** 发送邮箱登录验证码请求。 */
@Data
public class UserEmailLoginCodeRequest implements Serializable {
    private String email;

    private static final long serialVersionUID = 1L;
}
