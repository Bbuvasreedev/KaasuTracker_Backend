package com.kodegladiators.kaasutracker.controller;

import com.kodegladiators.kaasutracker.dto.EmiCreateRequest;
import com.kodegladiators.kaasutracker.dto.EmiDurationRequest;
import com.kodegladiators.kaasutracker.dto.EmiResource;
import com.kodegladiators.kaasutracker.dto.EmiStatusRequest;
import com.kodegladiators.kaasutracker.service.EmiService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/emis")
public class EmiController {

    private final EmiService emiService;

    public EmiController(EmiService emiService) {
        this.emiService = emiService;
    }

    @GetMapping
    public List<EmiResource> getUserEmis(@AuthenticationPrincipal Jwt jwt) {
        return emiService.list(jwt.getSubject());
    }

    @PostMapping
    public ResponseEntity<EmiResource> createEmi(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody EmiCreateRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        EmiService.CreationResult result = emiService.create(jwt.getSubject(), request, idempotencyKey);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.resource());
    }

    @PutMapping("/{emiId}/status")
    public EmiResource updateStatus(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive long emiId,
            @Valid @RequestBody EmiStatusRequest request
    ) {
        return emiService.updateStatus(jwt.getSubject(), emiId, request.status());
    }

    @PutMapping("/{emiId}/duration")
    public EmiResource setDuration(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive long emiId,
            @Valid @RequestBody EmiDurationRequest request
    ) {
        return emiService.setDuration(jwt.getSubject(), emiId, request);
    }
}
