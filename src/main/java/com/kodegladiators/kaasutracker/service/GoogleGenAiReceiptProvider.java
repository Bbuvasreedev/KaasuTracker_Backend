package com.kodegladiators.kaasutracker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kodegladiators.kaasutracker.dto.ReceiptExtractionResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

@Component
public class GoogleGenAiReceiptProvider implements ReceiptAiProvider {

    private static final String SYSTEM_PROMPT = """
            Extract only information explicitly visible on the receipt. Do not infer or invent missing values.
            Return one JSON object matching the required schema; unknown scalar fields must be null and unknown
            lists may be null. Use ISO dates (yyyy-MM-dd), ISO local time (HH:mm:ss), decimal numbers for money,
            a three-letter ISO currency code only when printed or unambiguously supported by the receipt, and
            confidenceScore from 0 to 1 only when available. Put uncertain field names in uncertainFields.
            """;

    private final ChatClient chatClient;
    private final BeanOutputConverter<ReceiptExtractionResponse> outputConverter =
            new BeanOutputConverter<>(ReceiptExtractionResponse.class);
    private final ObjectMapper strictMapper;
    private final String apiKey;

    public GoogleGenAiReceiptProvider(
            ChatClient.Builder chatClientBuilder,
            ObjectMapper objectMapper,
            @Value("${GCP_API_KEY:}") String apiKey
    ) {
        this.chatClient = chatClientBuilder.build();
        this.strictMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.apiKey = apiKey;
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public ReceiptExtractionResponse extract(byte[] receiptBytes, String detectedMimeType) {
        String result = chatClient.prompt()
                .system(SYSTEM_PROMPT + "\nJSON schema:\n" + outputConverter.getFormat())
                .user(user -> user
                        .text("Extract the receipt fields from the attached document.")
                        .media(
                                MimeTypeUtils.parseMimeType(detectedMimeType),
                                new ByteArrayResource(receiptBytes) {
                                    @Override
                                    public String getFilename() {
                                        return "receipt." + fileExtension(detectedMimeType);
                                    }
                                }))
                .call()
                .content();
        if (result == null || result.isBlank()) {
            throw new IllegalStateException("AI provider returned an empty response");
        }
        return parseResponse(strictMapper, result);
    }

    static ReceiptExtractionResponse parseResponse(ObjectMapper mapper, String result) {
        if (result == null || result.isBlank()) {
            throw new IllegalStateException("AI provider returned an empty response");
        }
        try {
            return mapper.readValue(result, ReceiptExtractionResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AI provider returned malformed structured data");
        }
    }

    private static String fileExtension(String mimeType) {
        return switch (mimeType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "application/pdf" -> "pdf";
            default -> "bin";
        };
    }
}
