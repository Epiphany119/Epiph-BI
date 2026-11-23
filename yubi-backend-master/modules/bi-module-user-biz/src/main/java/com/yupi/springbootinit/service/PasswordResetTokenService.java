package com.yupi.springbootinit.service;

import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 密码重置一次性验证码管理：
 * - 6 位加密随机数，5 分钟有效
 * - 同一账号 60 秒内限发一次
 * - 单次验证最多允许 5 次错误，超限自动作废；校验通过后立即核销
 */
@Component
public class PasswordResetTokenService {
    public static final long CODE_TTL_MS = 5 * 60 * 1000L;
    public static final long SEND_COOLDOWN_MS = 60 * 1000L;
    public static final int MAX_VERIFY_ATTEMPTS = 5;

    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, ResetEntry> store = new ConcurrentHashMap<>();
    private final Clock clock;

    public PasswordResetTokenService() {
        this(Clock.systemUTC());
    }

    PasswordResetTokenService(Clock clock) {
        this.clock = clock;
    }

    /** 为指定账号签发新的 6 位重置验证码。 */
    public String issueCode(String userAccount) {
        String key = normalize(userAccount);
        long now = clock.millis();
        String code = String.format(Locale.ROOT, "%06d", secureRandom.nextInt(1_000_000));
        store.compute(key, (k, existing) -> {
            if (existing != null && now - existing.issuedAtMs < SEND_COOLDOWN_MS) {
                long waitSec = Math.max(1L, (SEND_COOLDOWN_MS - (now - existing.issuedAtMs) + 999) / 1000);
                throw new BusinessException(ErrorCode.TOO_MANY_REQUEST, "请求过于频繁，请 " + waitSec + " 秒后再试");
            }
            return new ResetEntry(code, now, now + CODE_TTL_MS);
        });
        return code;
    }

    /** 校验并一次性核销，确保第 5 次错误既作废条目又抛异常。 */
    public void verifyAndConsumeStrict(String userAccount, String inputCode) {
        if (inputCode == null || inputCode.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "重置验证码不能为空");
        }
        String key = normalize(userAccount);
        String trimmedInput = inputCode.trim();
        long now = clock.millis();
        final String[] errorHolder = new String[1];
        store.compute(key, (k, existing) -> {
            if (existing == null || now > existing.expiresAtMs) {
                errorHolder[0] = "重置验证码不存在或已过期，请重新获取";
                return null;
            }
            boolean matched = MessageDigest.isEqual(
                    existing.code.getBytes(StandardCharsets.UTF_8),
                    trimmedInput.getBytes(StandardCharsets.UTF_8));
            if (!matched) {
                existing.failedAttempts++;
                if (existing.failedAttempts >= MAX_VERIFY_ATTEMPTS) {
                    errorHolder[0] = "验证码错误次数过多，已作废，请重新获取";
                    return null;
                }
                errorHolder[0] = "重置验证码错误";
                return existing;
            }
            return null;
        });
        if (errorHolder[0] != null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, errorHolder[0]);
        }
    }

    /** 邮件发送失败时撤销刚签发的验证码，避免用户被误锁 60 秒冷却。 */
    public void invalidate(String userAccount) {
        store.remove(normalize(userAccount));
    }

    private String normalize(String userAccount) {
        return userAccount == null ? "" : userAccount.trim().toLowerCase(Locale.ROOT);
    }

    private static final class ResetEntry {
        private final String code;
        private final long issuedAtMs;
        private final long expiresAtMs;
        private int failedAttempts;

        private ResetEntry(String code, long issuedAtMs, long expiresAtMs) {
            this.code = code;
            this.issuedAtMs = issuedAtMs;
            this.expiresAtMs = expiresAtMs;
        }
    }
}
