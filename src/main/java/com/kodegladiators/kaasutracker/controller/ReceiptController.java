package com.kodegladiators.kaasutracker.controller;

import com.kodegladiators.kaasutracker.dto.ReceiptExtractionResponse;
import com.kodegladiators.kaasutracker.service.ReceiptAnalysisService;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/receipts")
public class ReceiptController {

    private final ReceiptAnalysisService receiptAnalysisService;

    public ReceiptController(ReceiptAnalysisService receiptAnalysisService) {
        this.receiptAnalysisService = receiptAnalysisService;
    }

    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ReceiptExtractionResponse extract(
            @AuthenticationPrincipal Jwt jwt,
            @RequestPart("file") MultipartFile file
    ) {
        return receiptAnalysisService.extract(jwt.getSubject(), file);
    }
}
