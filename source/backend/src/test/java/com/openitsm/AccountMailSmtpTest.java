package com.openitsm;

import com.openitsm.common.exception.BusinessException;
import com.openitsm.config.AccountMailConfig;
import com.openitsm.service.impl.SmtpMailServiceImpl;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountMailSmtpTest {
    private final String token = "a".repeat(43);

    AccountMailConfig config(boolean enabled, String from, String baseUrl, MailProperties properties) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        return new AccountMailConfig(enabled, from, baseUrl, properties, environment);
    }
    MailProperties localProperties(int port) {
        var properties = new MailProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(port);
        properties.getProperties().put("mail.smtp.auth", "false");
        return properties;
    }

    @Test void disabledOrIncompleteConfigurationNeverConnectsToSmtp() {
        JavaMailSender sender = mock(JavaMailSender.class);
        var properties = localProperties(2525);
        var disabled = new SmtpMailServiceImpl(sender, config(false, "sender@example.com", "http://localhost:5173", properties));
        assertThatThrownBy(() -> disabled.send("member@example.com", "INVITE", token))
            .isInstanceOf(BusinessException.class).hasMessage("账号邮件发送配置暂不可用，请联系管理员");
        var incomplete = new SmtpMailServiceImpl(sender, config(true, "", "http://localhost:5173", properties));
        assertThatThrownBy(() -> incomplete.send("member@example.com", "INVITE", token)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(sender);
    }

    @Test void invalidAddressesTokensPurposesAndBaseUrlsAreRejectedBeforeSending() {
        JavaMailSender sender = mock(JavaMailSender.class);
        var properties = localProperties(2525);
        var service = new SmtpMailServiceImpl(sender, config(true, "sender@example.com", "http://localhost:5173", properties));
        assertThatThrownBy(() -> service.send("member@example.com\r\nBcc:other@example.com", "INVITE", token)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.send("member@example.com", "OTHER", token)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.send("member@example.com", "INVITE", "short")).isInstanceOf(IllegalArgumentException.class);
        for (String base : new String[] { "javascript:alert(1)", "https://name:password@example.com", "https://example.com?redirect=evil", "http://example.com" }) {
            var invalid = new SmtpMailServiceImpl(sender, config(true, "sender@example.com", base, properties));
            assertThatThrownBy(() -> invalid.send("member@example.com", "INVITE", token)).isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(sender);
    }

    @Test void transportUsesFiniteTimeoutsAndChecksTlsServerIdentity() {
        var config = config(false, "", "", localProperties(2525));
        JavaMailSenderImpl sender = config.javaMailSender();
        assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.connectiontimeout")).isEqualTo("5000");
        assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.timeout")).isEqualTo("10000");
        assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.writetimeout")).isEqualTo("10000");
        assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
    }

    @Test void externalSmtpRequiresTlsAuthenticationAndCredentials() {
        var properties = new MailProperties();
        properties.setHost("smtp.163.com");
        properties.setPort(465);
        properties.setUsername("sender@example.com");
        properties.setPassword("test-only-authorization");
        properties.getProperties().put("mail.smtp.auth", "true");
        var config = config(true, "sender@example.com", "http://localhost:5173", properties);
        assertThatThrownBy(config::requireReady).isInstanceOf(BusinessException.class);
        properties.getProperties().put("mail.smtp.ssl.enable", "true");
        assertThatCode(config::requireReady).doesNotThrowAnyException();
        properties.setPassword("");
        assertThatThrownBy(config::requireReady).isInstanceOf(BusinessException.class);
        properties.setPassword("test-only-authorization");
        properties.getProperties().put("mail.smtp.ssl.trust", "*");
        assertThatThrownBy(config::requireReady).isInstanceOf(BusinessException.class);
    }

    @Test void springSslFlagIsAppliedToTheActualTransport() {
        var properties = localProperties(465);
        properties.getSsl().setEnabled(true);
        var config = config(false, "", "", properties);
        assertThat(config.javaMailSender().getJavaMailProperties().getProperty("mail.smtp.ssl.enable")).isEqualTo("true");
    }

    @Test void productionLinksRequireHttps() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        var properties = localProperties(465);
        properties.getProperties().put("mail.smtp.ssl.enable", "true");
        properties.getProperties().put("mail.smtp.auth", "true");
        properties.setUsername("sender@example.com");
        properties.setPassword("test-only-authorization");
        var invalid = new AccountMailConfig(true, "sender@example.com", "http://localhost:5173", properties, environment);
        assertThatThrownBy(invalid::requireReady).isInstanceOf(BusinessException.class);
        var valid = new AccountMailConfig(true, "sender@example.com", "https://itsm.example.com", properties, environment);
        assertThatCode(valid::requireReady).doesNotThrowAnyException();
    }

    @Test void smtpFailuresExposeNoTokenOrCredentials() {
        JavaMailSender sender = mock(JavaMailSender.class);
        var config = config(true, "sender@example.com", "http://localhost:5173", localProperties(2525));
        when(sender.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailSendException("sensitive transport detail " + token)).when(sender).send(any(MimeMessage.class));
        assertThatThrownBy(() -> new SmtpMailServiceImpl(sender, config).send("member@example.com", "INVITE", token))
            .isInstanceOf(BusinessException.class).hasMessage("账号邮件投递暂不可用，请稍后重试").hasNoCause();
    }

    @Test void invitationAndResetAreDeliveredAsRealSmtpMimeMessages() throws Exception {
        for (String purpose : new String[] { "INVITE", "RESET" }) {
            try (var capture = new SmtpCapture()) {
                var config = config(true, "sender@example.com", "http://localhost:5173/", localProperties(capture.port()));
                new SmtpMailServiceImpl(config.javaMailSender(), config).send("member@example.com", purpose, token);
                MimeMessage received = capture.message();
                assertThat(received.getFrom()[0].toString()).isEqualTo("sender@example.com");
                assertThat(received.getAllRecipients()[0].toString()).isEqualTo("member@example.com");
                assertThat(received.getSubject()).isEqualTo(purpose.equals("INVITE") ? "邀请你加入 OpenITSM" : "重置你的 OpenITSM 密码");
                String body = (String) received.getContent();
                String path = purpose.equals("INVITE") ? "/activate" : "/reset-password";
                assertThat(body).contains("http://localhost:5173" + path + "?token=" + token)
                    .contains(purpose.equals("INVITE") ? "48 小时" : "30 分钟").contains("一次");
            }
        }
    }

    /** Minimal local test peer: accepts messages over TCP and never forwards to real recipients. */
    static final class SmtpCapture implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final Future<byte[]> received;
        SmtpCapture() throws IOException {
            server.setSoTimeout(10000);
            received = executor.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(10000);
                    var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                    reply(writer, "220 localhost test SMTP");
                    byte[] message = null;
                    for (String line; (line = reader.readLine()) != null;) {
                        if (line.equals("DATA")) {
                            reply(writer, "354 End with a dot");
                            var data = new StringBuilder();
                            for (String body; (body = reader.readLine()) != null && !body.equals(".");) {
                                data.append(body.startsWith("..") ? body.substring(1) : body).append("\r\n");
                            }
                            message = data.toString().getBytes(StandardCharsets.UTF_8);
                            reply(writer, "250 accepted");
                        } else if (line.equals("QUIT")) {
                            reply(writer, "221 bye");
                            break;
                        } else reply(writer, "250 OK");
                    }
                    if (message == null) throw new IOException("No SMTP DATA received");
                    return message;
                }
            });
        }
        static void reply(BufferedWriter writer, String message) throws IOException { writer.write(message + "\r\n"); writer.flush(); }
        int port() { return server.getLocalPort(); }
        MimeMessage message() throws Exception { return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(received.get(10, TimeUnit.SECONDS))); }
        @Override public void close() throws IOException { server.close(); executor.shutdownNow(); }
    }
}
