package com.kodegladiators.kaasutracker.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record ReceiptExtractionResponse(
        String merchant,
        LocalDate receiptDate,
        LocalTime receiptTime,
        BigDecimal totalAmount,
        BigDecimal subtotal,
        BigDecimal tax,
        BigDecimal discount,
        String currency,
        String suggestedCategory,
        String paymentMethod,
        String invoiceNumber,
        List<ReceiptLineItem> items,
        Double confidenceScore,
        List<String> uncertainFields
) {
}
