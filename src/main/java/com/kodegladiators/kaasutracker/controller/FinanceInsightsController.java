package com.kodegladiators.kaasutracker.controller;

import com.kodegladiators.kaasutracker.service.FinancialInsightsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/finance")
public class FinanceInsightsController {

    private final FinancialInsightsService financialInsightsService;

    public FinanceInsightsController(FinancialInsightsService financialInsightsService) {
        this.financialInsightsService = financialInsightsService;
    }

    @GetMapping("/insights")
    public ResponseEntity<String> insights(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        String insight = financialInsightsService.generateInsights(userId);
        return ResponseEntity.ok(insight);
    }
}

