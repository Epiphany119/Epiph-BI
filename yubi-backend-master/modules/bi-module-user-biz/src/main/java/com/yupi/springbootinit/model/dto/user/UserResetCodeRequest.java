package com.yupi.springbootinit.model.dto.user;

import lombok.Data;

import java.io.Serializable;

/** 获取密码重置验证码请求。 */
@Data
public class UserResetCodeRequest implements Serializable {
    private String userAccount;

    private static final long serialVersionUID = 1L;
}
