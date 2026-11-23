package com.yupi.springbootinit.service;

import com.yupi.springbootinit.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** QQ SMTP 密码重置发信服务单元测试。 */
class PasswordResetMailServiceTest {

    @Test
    void shouldSendFormattedResetCodeEmail() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        PasswordResetMailService service = new PasswordResetMailService(mailSender, "sender@qq.com");

        service.sendResetCode("alice123@qq.com", "alice", "654321");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage msg = captor.getValue();
        assertEquals("sender@qq.com", msg.getFrom());
        assertEquals("alice123@qq.com", msg.getTo()[0]);
        assertTrue(msg.getSubject().contains("Epiph-BI"));
        assertTrue(msg.getText().contains("654321"));
    }

    @Test
    void shouldThrowClearErrorWhenSmtpNotConfigured() {
        PasswordResetMailService service = new PasswordResetMailService(mock(JavaMailSender.class), "");

        assertThrows(BusinessException.class, () -> service.sendResetCode("alice@qq.com", "alice", "123456"));
    }

    @Test
    void shouldMaskEmailProperly() {
        PasswordResetMailService service = new PasswordResetMailService(null, "");

        assertEquals("12***6@qq.com", service.maskEmail("123456@qq.com"));
        assertEquals("a***@qq.com", service.maskEmail("ab@qq.com"));
    }
}
