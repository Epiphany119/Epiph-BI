package com.yupi.springbootinit.model.dto.user;

import lombok.Data;

import java.io.Serializable;

/** 忘记密码重置请求。 */
@Data
public class UserResetPasswordRequest implements Serializable {
    private String userAccount;
    private String resetCode;
    private String newPassword;
    private String checkPassword;

    private static final long serialVersionUID = 1L;
}
