package com.kodegladiators.kaasutracker.dto;

import com.kodegladiators.kaasutracker.model.EmiStatus;
import jakarta.validation.constraints.NotNull;

public record EmiStatusRequest(@NotNull EmiStatus status) {
}
