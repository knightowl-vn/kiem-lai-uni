package com.universe.identity.infrastructure.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.universe.identity.application.ports.PasswordResetEmailPort;

@Component
public class SpringMailPasswordResetAdapter
        implements PasswordResetEmailPort {

    private final JavaMailSender mailSender;
    private final String senderEmail;

    public SpringMailPasswordResetAdapter(
            JavaMailSender mailSender,
            @Value("${spring.mail.username}")
            String senderEmail
    ) {
        this.mailSender = mailSender;
        this.senderEmail = senderEmail;
    }

    @Override
    public void sendPasswordResetEmail(
            String recipientEmail,
            String resetLink
    ) {
        SimpleMailMessage message =
                new SimpleMailMessage();

        message.setFrom(senderEmail);
        message.setTo(recipientEmail);

        message.setSubject(
                "Đặt lại mật khẩu - Kiếm Lai Universe"
        );

        message.setText(
                "Bạn đã yêu cầu đặt lại mật khẩu.\n\n"
                + "Nhấn vào liên kết sau để tiếp tục:\n"
                + resetLink
                + "\n\n"
                + "Liên kết sẽ hết hạn sau 15 phút.\n\n"
                + "Nếu bạn không thực hiện yêu cầu này, "
                + "hãy bỏ qua email."
        );

        mailSender.send(message);
    }
}