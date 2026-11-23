package com.yupi.springbootinit.model.dto.user;

import lombok.Data;

import java.io.Serializable;

/** 校验验证码并确认绑定邮箱请求。 */
@Data
public class UserBindEmailRequest implements Serializable {
    private String email;
    private String verifyCode;
    private String userName;

    private static final long serialVersionUID = 1L;
}
