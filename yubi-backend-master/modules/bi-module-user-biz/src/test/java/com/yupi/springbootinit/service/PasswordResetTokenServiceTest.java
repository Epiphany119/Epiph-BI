package com.yupi.springbootinit.service;

import com.yupi.springbootinit.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 密码重置一次性验证码服务单元测试。 */
class PasswordResetTokenServiceTest {

    @Test
    void shouldIssueSixDigitCodeAndConsumeOnce() {
        MutableClock clock = new MutableClock(1_000_000L);
        PasswordResetTokenService service = new PasswordResetTokenService(clock);

        String code = service.issueCode("alice");

        assertEquals(6, code.length());
        assertDoesNotThrow(() -> service.verifyAndConsumeStrict("alice", code));
        assertThrows(BusinessException.class, () -> service.verifyAndConsumeStrict("alice", code));
    }

    @Test
    void shouldEnforceSendCooldownWithin60Seconds() {
        MutableClock clock = new MutableClock(1_000_000L);
        PasswordResetTokenService service = new PasswordResetTokenService(clock);

        service.issueCode("bob");
        assertThrows(BusinessException.class, () -> service.issueCode("bob"));

        clock.advanceMillis(PasswordResetTokenService.SEND_COOLDOWN_MS);
        assertDoesNotThrow(() -> service.issueCode("bob"));
    }

    @Test
    void shouldExpireCodeAfterFiveMinutes() {
        MutableClock clock = new MutableClock(1_000_000L);
        PasswordResetTokenService service = new PasswordResetTokenService(clock);

        String code = service.issueCode("charlie");
        clock.advanceMillis(PasswordResetTokenService.CODE_TTL_MS + 1);

        assertThrows(BusinessException.class, () -> service.verifyAndConsumeStrict("charlie", code));
    }

    @Test
    void shouldInvalidateCodeAfterFiveWrongAttempts() {
        MutableClock clock = new MutableClock(1_000_000L);
        PasswordResetTokenService service = new PasswordResetTokenService(clock);

        String code = service.issueCode("david");
        String wrong = "000000".equals(code) ? "999999" : "000000";
        for (int i = 0; i < PasswordResetTokenService.MAX_VERIFY_ATTEMPTS; i++) {
            assertThrows(BusinessException.class, () -> service.verifyAndConsumeStrict("david", wrong));
        }
        assertThrows(BusinessException.class, () -> service.verifyAndConsumeStrict("david", code));
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong millis;

        private MutableClock(long initialMillis) {
            this.millis = new AtomicLong(initialMillis);
        }

        private void advanceMillis(long delta) {
            millis.addAndGet(delta);
        }

        @Override
        public ZoneId getZone() { return ZoneId.of("UTC"); }

        @Override
        public Clock withZone(ZoneId zone) { return this; }

        @Override
        public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
    }
}
