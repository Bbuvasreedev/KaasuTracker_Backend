package com.kodegladiators.kaasutracker.service;

import com.kodegladiators.kaasutracker.dto.ExpenseAnalysisResponse;
import com.kodegladiators.kaasutracker.dto.ReceiptExtractionResponse;
import com.kodegladiators.kaasutracker.dto.ReceiptLineItem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReceiptAnalysisService {

    private static final String SUPPORTED_TYPES = "JPEG, PNG, WebP, or PDF";
    private final ReceiptAiProvider provider;
    private final ExecutorService executor;
    private final long maximumFileSize;
    private final long requestTimeoutMillis;

    public ReceiptAnalysisService(
            ReceiptAiProvider provider,
            @Qualifier("receiptAiExecutor") ExecutorService receiptAiExecutor,
            @Value("${kaasu.receipt.max-file-size-bytes:10485760}") long maximumFileSize,
            @Value("${kaasu.receipt.ai-timeout-millis:30000}") long requestTimeoutMillis
    ) {
        this.provider = provider;
        this.executor = receiptAiExecutor;
        this.maximumFileSize = maximumFileSize;
        this.requestTimeoutMillis = requestTimeoutMillis;
    }

    public ReceiptExtractionResponse extract(String authenticatedUserId, MultipartFile file) {
        if (authenticatedUserId == null || authenticatedUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required");
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipt file must not be empty");
        }
        if (file.getSize() > maximumFileSize) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Receipt file exceeds the upload limit");
        }
        if (!provider.isConfigured()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Receipt extraction is not configured on this server");
        }

        byte[] receiptBytes;
        try {
            receiptBytes = file.getBytes();
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipt file could not be read");
        }
        String mimeType = validateFile(file, receiptBytes);
        return requestExtraction(receiptBytes, mimeType);
    }

    public ExpenseAnalysisResponse analyzeReceipt(String base64Image) {
        if (base64Image == null || base64Image.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipt image must not be blank");
        }
        String encoded = base64Image.trim();
        String declaredMime = null;
        if (encoded.startsWith("data:")) {
            int comma = encoded.indexOf(',');
            if (comma < 0 || !encoded.substring(0, comma).endsWith(";base64")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipt data URI is malformed");
            }
            declaredMime = encoded.substring(5, encoded.indexOf(';')).toLowerCase(Locale.ROOT);
            encoded = encoded.substring(comma + 1);
        }
        byte[] bytes;
        try {
            bytes = java.util.Base64.getDecoder().decode(encoded.replaceAll("\\s", ""));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipt image is not valid base64");
        }
        if (bytes.length == 0 || bytes.length > maximumFileSize) {
            throw new ResponseStatusException(
                    bytes.length == 0 ? HttpStatus.BAD_REQUEST : HttpStatus.PAYLOAD_TOO_LARGE,
                    "Receipt image is empty or exceeds the upload limit");
        }
        if (!provider.isConfigured()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Receipt extraction is not configured on this server");
        }
        String mimeType = detectMimeType(bytes);
        if (mimeType.equals("application/pdf")
                || declaredMime != null && !normalizeMime(declaredMime).equals(mimeType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipt image type is unsupported or mismatched");
        }
        ReceiptExtractionResponse response = requestExtraction(bytes, mimeType);
        return new ExpenseAnalysisResponse(
                response.totalAmount(),
                response.merchant(),
                response.suggestedCategory(),
                response.receiptDate(),
                response.confidenceScore()
        );
    }

    private ReceiptExtractionResponse requestExtraction(byte[] bytes, String mimeType) {
        Future<ReceiptExtractionResponse> future;
        try {
            future = executor.submit(() -> provider.extract(bytes, mimeType));
        } catch (RejectedExecutionException exception) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Receipt extraction capacity is temporarily unavailable");
        }

        try {
            return validateResponse(future.get(requestTimeoutMillis, TimeUnit.MILLISECONDS));
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Receipt extraction timed out");
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Receipt extraction was interrupted");
        } catch (ExecutionException exception) {
            throw providerFailure(exception.getCause());
        }
    }

    private static ResponseStatusException providerFailure(Throwable exception) {
        Throwable cause = exception;
        while (cause != null) {
            String type = cause.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            String message = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase(Locale.ROOT);
            if (type.contains("timeout") || message.contains("timed out") || message.contains("timeout")) {
                return new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Receipt extraction timed out");
            }
            if (type.contains("ratelimit") || type.contains("too many requests")
                    || message.contains("429") || message.contains("rate limit")) {
                return new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS, "Receipt extraction provider is rate limited");
            }
            cause = cause.getCause();
        }
        return new ResponseStatusException(
                HttpStatus.BAD_GATEWAY, "Receipt extraction provider request failed");
    }

    private static ReceiptExtractionResponse validateResponse(ReceiptExtractionResponse response) {
        if (response == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Receipt provider returned no extraction data");
        }
        validateText(response.merchant(), 200, "merchant");
        validateText(response.suggestedCategory(), 100, "category");
        validateText(response.paymentMethod(), 80, "payment method");
        validateText(response.invoiceNumber(), 100, "invoice number");
        if (response.totalAmount() != null && response.totalAmount().signum() <= 0
                || response.subtotal() != null && response.subtotal().signum() < 0
                || response.tax() != null && response.tax().signum() < 0
                || response.discount() != null && response.discount().signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Receipt provider returned invalid amounts");
        }
        if (response.currency() != null && !response.currency().matches("(?i)[A-Z]{3}")) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Receipt provider returned invalid currency");
        }
        if (response.confidenceScore() != null
                && (response.confidenceScore() < 0 || response.confidenceScore() > 1)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Receipt provider returned invalid confidence");
        }
        if (response.items() != null) {
            if (response.items().size() > 200) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Receipt provider returned too many line items");
            }
            for (ReceiptLineItem item : response.items()) {
                if (item == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Receipt provider returned invalid line items");
                }
                validateText(item.description(), 300, "item description");
                if (item.quantity() != null && item.quantity().signum() <= 0
                        || item.unitPrice() != null && item.unitPrice().signum() < 0
                        || item.lineTotal() != null && item.lineTotal().signum() < 0) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_GATEWAY, "Receipt provider returned invalid line item amounts");
                }
            }
        }
        if (response.uncertainFields() != null) {
            List<String> knownFields = List.of(
                    "merchant", "receiptDate", "receiptTime", "totalAmount", "subtotal",
                    "tax", "discount", "currency", "suggestedCategory", "paymentMethod",
                    "invoiceNumber", "items");
            if (response.uncertainFields().stream().anyMatch(field -> !knownFields.contains(field))) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Receipt provider returned invalid uncertainty fields");
            }
        }
        return new ReceiptExtractionResponse(
                trim(response.merchant()),
                response.receiptDate(),
                response.receiptTime(),
                response.totalAmount(),
                response.subtotal(),
                response.tax(),
                response.discount(),
                response.currency() == null ? null : response.currency().toUpperCase(Locale.ROOT),
                trim(response.suggestedCategory()),
                trim(response.paymentMethod()),
                trim(response.invoiceNumber()),
                response.items() == null ? null : response.items().stream()
                        .map(item -> new ReceiptLineItem(
                                trim(item.description()), item.quantity(), item.unitPrice(), item.lineTotal()))
                        .toList(),
                response.confidenceScore(),
                response.uncertainFields() == null ? null : List.copyOf(response.uncertainFields())
        );
    }

    private static void validateText(String value, int maximumLength, String field) {
        if (value != null && value.length() > maximumLength) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Receipt provider returned an invalid " + field);
        }
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String validateFile(MultipartFile file, byte[] bytes) {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.lastIndexOf('.') < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipt file extension is required");
        }
        String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String detected = detectMimeType(bytes);
        String expectedExtension = switch (detected) {
            case "image/jpeg" -> extension.equals("jpg") || extension.equals("jpeg") ? "image/jpeg" : null;
            case "image/png" -> extension.equals("png") ? "image/png" : null;
            case "image/webp" -> extension.equals("webp") ? "image/webp" : null;
            case "application/pdf" -> extension.equals("pdf") ? "application/pdf" : null;
            default -> null;
        };
        if (!detected.equals(expectedExtension)) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Receipt extension does not match supported file content");
        }
        String declared = file.getContentType();
        if (declared != null && !declared.isBlank()
                && !declared.equalsIgnoreCase("application/octet-stream")
                && !normalizeMime(declared).equals(detected)) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Receipt content type does not match file content");
        }
        return detected;
    }

    private static String detectMimeType(byte[] bytes) {
        if (bytes.length >= 33
                && hasPrefix(bytes, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
                && hasAsciiAt(bytes, 12, "IHDR")
                && hasAsciiAt(bytes, bytes.length - 8, "IEND")) {
            return "image/png";
        }
        if (bytes.length >= 4
                && hasPrefix(bytes, 0xff, 0xd8)
                && (bytes[bytes.length - 2] & 0xff) == 0xff
                && (bytes[bytes.length - 1] & 0xff) == 0xd9) {
            return "image/jpeg";
        }
        if (bytes.length >= 12 && hasAsciiAt(bytes, 0, "RIFF") && hasAsciiAt(bytes, 8, "WEBP")) {
            long riffLength = (bytes[4] & 0xffL)
                    | (bytes[5] & 0xffL) << 8
                    | (bytes[6] & 0xffL) << 16
                    | (bytes[7] & 0xffL) << 24;
            if (riffLength == bytes.length - 8L) {
                return "image/webp";
            }
        }
        if (bytes.length >= 16 && hasAsciiAt(bytes, 0, "%PDF-")
                && new String(bytes, Math.max(0, bytes.length - 1024), Math.min(1024, bytes.length),
                        StandardCharsets.US_ASCII).contains("%%EOF")) {
            return "application/pdf";
        }
        throw new ResponseStatusException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Receipt must be a valid " + SUPPORTED_TYPES + " file");
    }

    private static String normalizeMime(String mimeType) {
        String normalized = mimeType.toLowerCase(Locale.ROOT);
        return normalized.equals("image/jpg") ? "image/jpeg" : normalized;
    }

    private static boolean hasPrefix(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if ((bytes[index] & 0xff) != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasAsciiAt(byte[] bytes, int offset, String value) {
        if (offset < 0 || bytes.length < offset + value.length()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (bytes[offset + index] != value.charAt(index)) {
                return false;
            }
        }
        return true;
    }
}
