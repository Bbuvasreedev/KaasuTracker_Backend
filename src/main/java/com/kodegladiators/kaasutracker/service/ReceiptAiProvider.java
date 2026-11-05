package com.kodegladiators.kaasutracker.service;

import com.kodegladiators.kaasutracker.dto.ReceiptExtractionResponse;

public interface ReceiptAiProvider {

    default boolean isConfigured() {
        return true;
    }

    ReceiptExtractionResponse extract(byte[] receiptBytes, String detectedMimeType);
}
