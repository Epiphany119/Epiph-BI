package com.yupi.springbootinit.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * 基于原生 JDK 17 SSLSocket 的轻量 SMTPS (465) 客户端：
 * 彻底绕开 Spring Boot 2.7 内置 jakarta.mail 1.6.7 SocketFetcher 在 JDK 17 下与 QQ 邮箱的 TLS 握手缺陷，
 * 并强制 Proxy.NO_PROXY 直连与 IPv4/IPv6 双栈自动回退。
 */
@Slf4j
public class DirectSmtpsClient {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int IO_TIMEOUT_MS = 10000;

    private final String host;
    private final int port;
    private final String username;
    private final String password;

    public DirectSmtpsClient(String host, int port, String username, String password) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    public void sendTextMail(String from, String to, String subject, String body) {
        try (SSLSocket socket = openDirectTlsSocket();
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            expect(readResponse(reader), "220", "SMTP greeting");

            sendLine(writer, "EHLO epiph-bi.local");
            expect(readResponse(reader), "250", "EHLO");

            sendLine(writer, "AUTH LOGIN");
            expect(readResponse(reader), "334", "AUTH LOGIN");

            sendLine(writer, base64(username));
            expect(readResponse(reader), "334", "AUTH username");

            sendLine(writer, base64(password));
            String authResp = readResponse(reader);
            if (!authResp.startsWith("235")) {
                throw new MailAuthenticationException("SMTP 认证被拒绝: " + authResp);
            }

            sendLine(writer, "MAIL FROM:<" + from + ">");
            expect(readResponse(reader), "250", "MAIL FROM");

            sendLine(writer, "RCPT TO:<" + to + ">");
            expect(readResponse(reader), "250", "RCPT TO");

            sendLine(writer, "DATA");
            expect(readResponse(reader), "354", "DATA");

            String encodedSubject = "=?UTF-8?B?" + base64(subject) + "?=";
            String encodedBody = Base64.getMimeEncoder(76, new byte[]{'\r', '\n'})
                    .encodeToString(body.getBytes(StandardCharsets.UTF_8));

            writer.write("From: <" + from + ">\r\n");
            writer.write("To: <" + to + ">\r\n");
            writer.write("Subject: " + encodedSubject + "\r\n");
            writer.write("MIME-Version: 1.0\r\n");
            writer.write("Content-Type: text/plain; charset=UTF-8\r\n");
            writer.write("Content-Transfer-Encoding: base64\r\n");
            writer.write("\r\n");
            writer.write(encodedBody);
            writer.write("\r\n.\r\n");
            writer.flush();

            String dataResp = readResponse(reader);
            expect(dataResp, "250", "DATA commit");
            log.info("[DirectSMTPS] mail accepted by {}:{} -> {}", host, port, dataResp);

            try {
                sendLine(writer, "QUIT");
                readResponse(reader);
            } catch (IOException ignored) {
                // 邮件已被服务器 250 确认接收，忽略关闭阶段异常
            }
        } catch (MailAuthenticationException e) {
            throw e;
        } catch (IOException e) {
            throw new MailSendException("直连 SMTPS 发送失败 (" + host + ":" + port + "): " + e.getMessage(), e);
        }
    }

    private SSLSocket openDirectTlsSocket() throws IOException {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        InetAddress[] addresses = InetAddress.getAllByName(host);
        log.info("[DirectSMTPS] resolved {}:{} -> {} addresses", host, port, addresses.length);
        IOException lastError = null;
        for (InetAddress addr : addresses) {
            Socket raw = new Socket(Proxy.NO_PROXY);
            try {
                log.info("[DirectSMTPS] connecting direct TLS to {}:{} via {}", host, port, addr.getHostAddress());
                raw.connect(new InetSocketAddress(addr, port), CONNECT_TIMEOUT_MS);
                raw.setSoTimeout(IO_TIMEOUT_MS);
                SSLSocket ssl = (SSLSocket) factory.createSocket(raw, host, port, true);
                ssl.setSoTimeout(IO_TIMEOUT_MS);
                ssl.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                SSLParameters params = ssl.getSSLParameters();
                params.setServerNames(List.of(new SNIHostName(host)));
                ssl.setSSLParameters(params);
                ssl.startHandshake();
                log.info("[DirectSMTPS] TLS handshake OK via {} ({})",
                        addr.getHostAddress(), ssl.getSession().getProtocol());
                return ssl;
            } catch (IOException e) {
                lastError = e;
                log.warn("[DirectSMTPS] failed via {}: {} - {}",
                        addr.getHostAddress(), e.getClass().getSimpleName(), e.getMessage());
                try {
                    raw.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
        throw lastError != null ? lastError : new IOException("无法连接 SMTP 主机 " + host + ":" + port);
    }

    private void sendLine(BufferedWriter writer, String line) throws IOException {
        writer.write(line);
        writer.write("\r\n");
        writer.flush();
    }

    private String readResponse(BufferedReader reader) throws IOException {
        String lastLine = null;
        while (true) {
            String line = reader.readLine();
            if (line == null) {
                throw new IOException("SMTP 服务器提前关闭连接");
            }
            lastLine = line;
            if (line.length() < 4 || line.charAt(3) == ' ') {
                return lastLine;
            }
        }
    }

    private void expect(String response, String expectedCode, String step) throws IOException {
        if (response == null || !response.startsWith(expectedCode)) {
            throw new IOException(step + " 期望 " + expectedCode + "，实际返回: " + response);
        }
    }

    private String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
