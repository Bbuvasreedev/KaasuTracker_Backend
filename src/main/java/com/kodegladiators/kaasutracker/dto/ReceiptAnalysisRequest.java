package com.kodegladiators.kaasutracker.dto;

import jakarta.validation.constraints.NotBlank;

public record ReceiptAnalysisRequest(@NotBlank String base64Image) {
}
