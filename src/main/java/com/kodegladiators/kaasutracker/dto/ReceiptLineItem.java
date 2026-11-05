package com.kodegladiators.kaasutracker.dto;

import java.math.BigDecimal;

public record ReceiptLineItem(
        String description,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal
) {
}
