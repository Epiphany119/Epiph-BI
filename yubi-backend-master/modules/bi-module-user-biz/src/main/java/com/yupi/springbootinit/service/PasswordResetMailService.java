package com.yupi.springbootinit.service;

import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 基于 Spring Mail（QQ 邮箱 SMTP）的验证码发信服务。
 * 生产环境默认严格要求真实发信；本地开发可在 application-local.yml 开启
 * app.mail.dev-fallback-on-error=true，在代理拦截 465 端口时自动降级为本地演示兜底。
 */
@Service
@Slf4j
public class PasswordResetMailService {

    private final JavaMailSender mailSender;
    private final String fromEmail;
    private final boolean devFallbackOnError;

    @Autowired
    public PasswordResetMailService(@Autowired(required = false) JavaMailSender mailSender,
                                    @Value("${spring.mail.username:}") String fromEmail,
                                    @Value("${app.mail.dev-fallback-on-error:false}") boolean devFallbackOnError) {
        this.mailSender = mailSender;
        this.fromEmail = fromEmail;
        this.devFallbackOnError = devFallbackOnError;
    }

    /** 兼容既有双参单元测试构造入口（默认关闭本地兜底）。 */
    public PasswordResetMailService(JavaMailSender mailSender, String fromEmail) {
        this(mailSender, fromEmail, false);
    }

    /**
     * 发送 6 位密码重置验证码到用户绑定邮箱。
     *
     * @return true 表示已通过 QQ SMTP 真实发送；false 表示仅在本地开发模式下触发了兜底降级
     */
    public boolean sendResetCode(String toEmail, String userAccount, String resetCode) {
        return sendCodeEmail(
                "PasswordResetMail",
                toEmail,
                userAccount,
                resetCode,
                "【Epiph-BI】密码重置验证码",
                "您好，" + userAccount + "：\n\n"
                        + "您正在申请重置 Epiph-BI 账户密码，本次一次性验证码为：\n\n"
                        + "    " + resetCode + "\n\n"
                        + "验证码 5 分钟内有效，验证成功后立即失效。如非本人操作，请忽略此邮件。\n");
    }

    /**
     * 发送 6 位绑定邮箱验证码，验证邮箱归属权后再允许入库。
     *
     * @return true 表示已通过 QQ SMTP 真实发送；false 表示仅在本地开发模式下触发了兜底降级
     */
    public boolean sendBindEmailCode(String toEmail, String userAccount, String verifyCode) {
        return sendCodeEmail(
                "BindEmailMail",
                toEmail,
                userAccount,
                verifyCode,
                "【Epiph-BI】绑定邮箱验证码",
                "您好，" + userAccount + "：\n\n"
                        + "您正在为 Epiph-BI 账号绑定此邮箱，本次一次性验证码为：\n\n"
                        + "    " + verifyCode + "\n\n"
                        + "验证码 5 分钟内有效，验证成功后立即失效。如非本人操作，请忽略此邮件。\n");
    }

    private boolean sendCodeEmail(String tag, String toEmail, String userAccount, String code,
                                  String subject, String text) {
        if (mailSender == null || StringUtils.isBlank(fromEmail)) {
            if (devFallbackOnError) {
                log.warn("[{}][DevFallback] 未配置发信账号，启用本地开发验证码兜底，userAccount={}, to={}, code={}",
                        tag, userAccount, maskEmail(toEmail), code);
                return false;
            }
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "邮箱发信服务尚未配置，请在 application-local.yml 中填写 QQ 邮箱与 SMTP 授权码");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail.trim());
        message.setTo(toEmail.trim());
        message.setSubject(subject);
        message.setText(text);
        try {
            mailSender.send(message);
            log.info("[{}] sent verification code to {} for userAccount={}", tag, maskEmail(toEmail), userAccount);
            return true;
        } catch (MailException e) {
            if (devFallbackOnError) {
                log.warn("[{}][DevFallback] QQ SMTP 发送失败（可能被本机代理拦截 465 端口），已降级为本地开发兜底，userAccount={}, to={}, code={}, reason={}",
                        tag, userAccount, maskEmail(toEmail), code, e.getMessage());
                return false;
            }
            log.error("[{}] failed to send email to {} for userAccount={}", tag, maskEmail(toEmail), userAccount, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "验证码邮件发送失败，请检查 QQ SMTP 发件邮箱与授权码配置");
        }
    }

    /** 对邮箱进行脱敏，例如 123456@qq.com -> 12***6@qq.com。 */
    public String maskEmail(String email) {
        if (StringUtils.isBlank(email) || !email.contains("@")) {
            return "***";
        }
        String trimmed = email.trim();
        int at = trimmed.indexOf('@');
        String local = trimmed.substring(0, at);
        String domain = trimmed.substring(at);
        if (local.length() <= 2) {
            return local.charAt(0) + "***" + domain;
        }
        return local.substring(0, 2) + "***" + local.charAt(local.length() - 1) + domain;
    }
}
