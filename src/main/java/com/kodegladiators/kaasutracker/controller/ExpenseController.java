package com.kodegladiators.kaasutracker.controller;

import com.kodegladiators.kaasutracker.dto.ExpenseAnalysisResponse;
import com.kodegladiators.kaasutracker.dto.ExpenseDto;
import com.kodegladiators.kaasutracker.dto.ExpenseResource;
import com.kodegladiators.kaasutracker.dto.ReceiptAnalysisRequest;
import com.kodegladiators.kaasutracker.service.ExpenseService;
import com.kodegladiators.kaasutracker.service.ReceiptAnalysisService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/expenses")
@Validated
public class ExpenseController {

    private final ExpenseService expenseService;
    private final ReceiptAnalysisService receiptAnalysisService;

    public ExpenseController(
            ExpenseService expenseService,
            ReceiptAnalysisService receiptAnalysisService
    ) {
        this.expenseService = expenseService;
        this.receiptAnalysisService = receiptAnalysisService;
    }

    @PostMapping
    public ResponseEntity<ExpenseResource> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ExpenseDto expenseDto,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        String userId = jwt.getSubject();
        ExpenseService.CreationResult result = expenseService.create(userId, expenseDto, idempotencyKey);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.resource());
    }

    @GetMapping
    public ResponseEntity<List<ExpenseResource>> findAll(
            @AuthenticationPrincipal Jwt jwt
    ) {
        String userId = jwt.getSubject();
        return ResponseEntity.ok(expenseService.findAllByUser(userId));
    }

    @GetMapping("/{expenseId}")
    public ResponseEntity<ExpenseResource> findById(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive Long expenseId
    ) {
        return ResponseEntity.ok(expenseService.findById(jwt.getSubject(), expenseId));
    }

    @DeleteMapping("/{expenseId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive Long expenseId
    ) {
        expenseService.delete(jwt.getSubject(), expenseId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/analyze")
    public ResponseEntity<ExpenseAnalysisResponse> analyzeReceipt(
            @Valid @RequestBody ReceiptAnalysisRequest request
    ) {
        return ResponseEntity.ok(receiptAnalysisService.analyzeReceipt(request.base64Image()));
    }
}