package com.rcb.notifier;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Sends Telegram messages and photos via the official Telegram Bot API.
 *
 * Methods:
 *   sendTicketAlert(url)        — formatted "tickets are live!" text message
 *   sendMessage(text)           — plain text message
 *   sendPhoto(imageBytes, cap)  — sends an image with a caption (for QR codes, screenshots)
 */
public class TelegramNotifier {

    private final String botToken;
    private final String chatId;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    public TelegramNotifier(Config config) {
        this.botToken = config.getTelegramBotToken();
        this.chatId   = config.getTelegramChatId();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Sends the standard "RCB tickets are live!" alert with timestamp.
     */
    public void sendTicketAlert(String ticketUrl) {
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a"));

        String message = """
                🚨 RCB Tickets are LIVE!
                👉 Buy now: %s
                🕐 Detected at: %s
                """.formatted(ticketUrl, timestamp);

        sendText(message);
    }

    /**
     * Sends a plain text message to Telegram.
     * Used for status updates, errors, UPI deep links, etc.
     */
    public void sendMessage(String text) {
        sendText(text);
    }

    /**
     * Sends an image (PNG/JPEG bytes) with a caption to Telegram.
     * Used for QR code screenshots and booking page screenshots.
     *
     * @param imageBytes raw bytes of the PNG/JPEG image
     * @param caption    text shown below the image in Telegram
     */
    public void sendPhoto(byte[] imageBytes, String caption) {
        String boundary = "----TelegramBoundary" + System.currentTimeMillis();
        byte[] body = buildMultipartBody(boundary, imageBytes, caption);

        String url = "https://api.telegram.org/bot%s/sendPhoto".formatted(botToken);

        System.out.println("[TelegramNotifier] Sending photo (" + imageBytes.length + " bytes)...");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                System.out.println("[TelegramNotifier] Photo sent successfully!");
            } else {
                System.err.println("[TelegramNotifier] Photo send failed: "
                        + response.statusCode() + " — " + response.body());
            }

        } catch (Exception e) {
            System.err.println("[TelegramNotifier] Failed to send photo: " + e.getMessage());
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Sends a plain text message via Telegram sendMessage API.
     */
    private void sendText(String message) {
        String encodedMessage = URLEncoder.encode(message, StandardCharsets.UTF_8);
        String url = "https://api.telegram.org/bot%s/sendMessage?chat_id=%s&text=%s"
                .formatted(botToken, chatId, encodedMessage);

        System.out.println("[TelegramNotifier] Sending message...");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                System.out.println("[TelegramNotifier] Message sent successfully!");
            } else {
                System.err.println("[TelegramNotifier] Unexpected status: "
                        + response.statusCode() + " — " + response.body());
            }

        } catch (Exception e) {
            System.err.println("[TelegramNotifier] Failed to send message: " + e.getMessage());
        }
    }

    /**
     * Builds a multipart/form-data body for the Telegram sendPhoto API.
     *
     * Structure:
     *   --boundary
     *   Content-Disposition: form-data; name="chat_id"
     *   <chatId>
     *   --boundary
     *   Content-Disposition: form-data; name="caption"
     *   <caption>
     *   --boundary
     *   Content-Disposition: form-data; name="photo"; filename="image.png"
     *   Content-Type: image/png
     *   <image bytes>
     *   --boundary--
     */
    private byte[] buildMultipartBody(String boundary, byte[] photo, String caption) {
        String CRLF = "\r\n";
        String sep  = "--" + boundary + CRLF;

        StringBuilder textParts = new StringBuilder();

        // chat_id field
        textParts.append(sep);
        textParts.append("Content-Disposition: form-data; name=\"chat_id\"").append(CRLF);
        textParts.append(CRLF);
        textParts.append(chatId).append(CRLF);

        // caption field (only if non-empty)
        if (caption != null && !caption.isBlank()) {
            textParts.append(sep);
            textParts.append("Content-Disposition: form-data; name=\"caption\"").append(CRLF);
            textParts.append(CRLF);
            textParts.append(caption).append(CRLF);
        }

        // photo file header (binary data follows)
        textParts.append(sep);
        textParts.append("Content-Disposition: form-data; name=\"photo\"; filename=\"image.png\"").append(CRLF);
        textParts.append("Content-Type: image/png").append(CRLF);
        textParts.append(CRLF);

        byte[] headerBytes  = textParts.toString().getBytes(StandardCharsets.UTF_8);
        byte[] closingBytes = (CRLF + "--" + boundary + "--" + CRLF).getBytes(StandardCharsets.UTF_8);

        // Concatenate: headers + photo bytes + closing boundary
        byte[] result = new byte[headerBytes.length + photo.length + closingBytes.length];
        System.arraycopy(headerBytes,  0, result, 0,                                  headerBytes.length);
        System.arraycopy(photo,        0, result, headerBytes.length,                 photo.length);
        System.arraycopy(closingBytes, 0, result, headerBytes.length + photo.length,  closingBytes.length);

        return result;
    }
}
