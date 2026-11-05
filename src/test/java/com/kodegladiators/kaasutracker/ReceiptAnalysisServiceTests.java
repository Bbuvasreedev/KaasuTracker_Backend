package com.kodegladiators.kaasutracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kodegladiators.kaasutracker.dto.ReceiptExtractionResponse;
import com.kodegladiators.kaasutracker.service.ReceiptAiProvider;
import com.kodegladiators.kaasutracker.service.ReceiptAnalysisService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class ReceiptAnalysisServiceTests {

    private final ReceiptAiProvider provider = mock(ReceiptAiProvider.class);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ReceiptAnalysisService service;

    @BeforeEach
    void setUp() {
        when(provider.isConfigured()).thenReturn(true);
        service = new ReceiptAnalysisService(provider, executor, 100, 100);
    }

    @AfterEach
    void shutDownExecutor() {
        executor.shutdownNow();
    }

    @Test
    void acceptsImageWithMatchingSignatureAndPreservesUnknownValues() {
        when(provider.extract(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("image/jpeg")))
                .thenReturn(new ReceiptExtractionResponse(
                        "Merchant", LocalDate.of(2026, 5, 1), null, new BigDecimal("12.50"),
                        null, null, null, "INR", null, null, null, null, 0.9, null));
        MockMultipartFile file = new MockMultipartFile(
                "file", "receipt.jpg", "image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9});

        ReceiptExtractionResponse response = service.extract("user-1", file);

        assertEquals(new BigDecimal("12.50"), response.totalAmount());
        assertNull(response.receiptTime());
    }

    @Test
    void acceptsPdfAndUsesDetectedContentType() {
        when(provider.extract(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("application/pdf")))
                .thenReturn(emptyResponse());
        MockMultipartFile file = new MockMultipartFile(
                "file", "receipt.pdf", "application/pdf", "%PDF-1.4\n%%EOF\n\n".getBytes());

        service.extract("user-1", file);
    }

    @Test
    void rejectsEmptyOversizedAndMalformedFiles() {
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.extract("user-1",
                new MockMultipartFile("file", "empty.jpg", "image/jpeg", new byte[0])));
        assertStatus(HttpStatus.PAYLOAD_TOO_LARGE, () -> service.extract("user-1",
                new MockMultipartFile("file", "large.jpg", "image/jpeg", new byte[101])));
        assertStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE, () -> service.extract("user-1",
                new MockMultipartFile("file", "fake.jpg", "image/jpeg", "not an image".getBytes())));
    }

    @Test
    void rejectsMismatchedExtensionAndMimeType() {
        assertStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE, () -> service.extract("user-1",
                new MockMultipartFile("file", "receipt.png", "image/jpeg",
                        new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9})));
        assertStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE, () -> service.extract("user-1",
                new MockMultipartFile("file", "receipt.jpg", "image/png",
                        new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9})));
    }

    @Test
    void rejectsExtractionWhenProviderIsNotConfigured() {
        when(provider.isConfigured()).thenReturn(false);
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE, () -> service.extract("user-1",
                new MockMultipartFile("file", "receipt.jpg", "image/jpeg",
                        new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9})));
    }

    @Test
    void mapsProviderTimeoutWithoutLeakingProviderDetails() {
        when(provider.extract(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    TimeUnit.SECONDS.sleep(1);
                    return emptyResponse();
                });
        service = new ReceiptAnalysisService(provider, executor, 10, 1);
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.extract("user-1",
                new MockMultipartFile("file", "receipt.jpg", "image/jpeg",
                        new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9})));
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, exception.getStatusCode());
    }

    @Test
    void mapsProviderRateLimitToTooManyRequests() {
        org.mockito.Mockito.reset(provider);
        when(provider.isConfigured()).thenReturn(true);
        when(provider.extract(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("429 rate limit"));
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.extract("user-1",
                new MockMultipartFile("file", "receipt.jpg", "image/jpeg",
                        new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9})));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.getStatusCode());
    }

    @Test
    void rejectsInvalidProviderAmounts() {
        when(provider.extract(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new ReceiptExtractionResponse(
                        null, null, null, new BigDecimal("-1"), null, null, null,
                        null, null, null, null, null, null, null));
        assertStatus(HttpStatus.BAD_GATEWAY, () -> service.extract("user-1",
                new MockMultipartFile("file", "receipt.jpg", "image/jpeg",
                        new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9})));
    }

    private static ReceiptExtractionResponse emptyResponse() {
        return new ReceiptExtractionResponse(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static void assertStatus(HttpStatus status, Runnable action) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(status, exception.getStatusCode());
    }
}
