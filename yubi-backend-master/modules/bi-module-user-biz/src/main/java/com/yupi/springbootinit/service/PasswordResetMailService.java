package com.yupi.springbootinit.service;

import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

/**
 * 基于 QQ 邮箱 SMTPS (465) 的验证码发信服务（支持绑定邮箱、邮箱登录、忘记密码重置）。
 * 运行时使用原生 JDK 17 DirectSmtpsClient 直连发信，彻底避开 jakarta.mail 1.6.7 的 TLS 握手问题。
 */
@Service
@Slf4j
public class PasswordResetMailService {

    private final JavaMailSender mailSender;
    private final DirectSmtpsClient directSmtpsClient;
    private final String fromEmail;
    private final boolean devFallbackOnError;

    @Autowired
    public PasswordResetMailService(@Autowired(required = false) JavaMailSender mailSender,
                                    @Value("${spring.mail.username:}") String fromEmail,
                                    @Value("${app.mail.dev-fallback-on-error:false}") boolean devFallbackOnError) {
        this.mailSender = mailSender;
        this.fromEmail = fromEmail;
        this.devFallbackOnError = devFallbackOnError;
        this.directSmtpsClient = buildDirectClient(mailSender, fromEmail);
    }

    /** 兼容既有双参单元测试构造入口（Mock JavaMailSender 时走 mailSender.send）。 */
    public PasswordResetMailService(JavaMailSender mailSender, String fromEmail) {
        this.mailSender = mailSender;
        this.directSmtpsClient = null;
        this.fromEmail = fromEmail;
        this.devFallbackOnError = false;
    }

    private DirectSmtpsClient buildDirectClient(JavaMailSender source, String senderEmail) {
        if (!(source instanceof JavaMailSenderImpl)) {
            return null;
        }
        JavaMailSenderImpl impl = (JavaMailSenderImpl) source;
        String user = StringUtils.defaultIfBlank(impl.getUsername(), senderEmail);
        String pass = impl.getPassword();
        if (StringUtils.isAnyBlank(user, pass)) {
            return null;
        }
        String host = StringUtils.defaultIfBlank(impl.getHost(), "smtp.qq.com");
        int port = impl.getPort() > 0 ? impl.getPort() : 465;
        log.info("[DirectSMTPS] enabled native JDK17 SMTPS client for {}:{} (user={})",
                host, port, maskEmail(user));
        return new DirectSmtpsClient(host, port, user.trim(), pass.trim());
    }

    /** 发送 6 位密码重置验证码到用户绑定邮箱。 */
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

    /** 发送 6 位绑定邮箱验证码，验证邮箱归属权后再允许入库。 */
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

    /** 发送 6 位邮箱登录验证码。 */
    public boolean sendLoginCode(String toEmail, String userAccount, String loginCode) {
        return sendCodeEmail(
                "EmailLoginMail",
                toEmail,
                userAccount,
                loginCode,
                "【Epiph-BI】邮箱登录验证码",
                "您好，" + userAccount + "：\n\n"
                        + "您正在使用邮箱验证码登录 Epiph-BI，本次一次性验证码为：\n\n"
                        + "    " + loginCode + "\n\n"
                        + "验证码 5 分钟内有效，验证成功后立即失效。如非本人操作，请忽略此邮件。\n");
    }

    private boolean sendCodeEmail(String tag, String toEmail, String userAccount, String code,
                                  String subject, String text) {
        if ((directSmtpsClient == null && mailSender == null) || StringUtils.isBlank(fromEmail)) {
            if (devFallbackOnError) {
                log.warn("[{}][DevFallback] 未配置发信账号，启用本地开发验证码兜底，userAccount={}, to={}, code={}",
                        tag, userAccount, maskEmail(toEmail), code);
                return false;
            }
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "邮箱发信服务尚未配置，请在 application-local.yml 中填写 QQ 邮箱与 SMTP 授权码");
        }
        try {
            if (directSmtpsClient != null) {
                directSmtpsClient.sendTextMail(fromEmail.trim(), toEmail.trim(), subject, text);
            } else {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(fromEmail.trim());
                message.setTo(toEmail.trim());
                message.setSubject(subject);
                message.setText(text);
                mailSender.send(message);
            }
            log.info("[{}] sent verification code to {} for userAccount={}", tag, maskEmail(toEmail), userAccount);
            return true;
        } catch (MailAuthenticationException e) {
            log.error("[{}] SMTP authentication failed for fromEmail={}", tag, maskEmail(fromEmail), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "QQ 邮箱 SMTP 认证失败（535），请检查发件邮箱与 16 位授权码是否有效");
        } catch (MailException e) {
            if (devFallbackOnError) {
                log.warn("[{}][DevFallback] QQ SMTP 发送失败，已降级为本地开发兜底，userAccount={}, to={}, code={}, reason={}",
                        tag, userAccount, maskEmail(toEmail), code, e.getMessage());
                return false;
            }
            log.error("[{}] failed to send email to {} for userAccount={}", tag, maskEmail(toEmail), userAccount, e);
            Throwable root = rootCause(e);
            String detail = root.getClass().getSimpleName()
                    + (StringUtils.isNotBlank(root.getMessage()) ? ": " + root.getMessage() : "");
            if (detail.length() > 160) {
                detail = detail.substring(0, 160) + "...";
            }
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "验证码邮件发送失败（" + detail + "）");
        }
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable cur = throwable;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
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
