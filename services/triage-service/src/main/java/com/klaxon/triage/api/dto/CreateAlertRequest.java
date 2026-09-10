package com.klaxon.triage.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record CreateAlertRequest(
        @NotBlank @Size(max = 60)   String source,
        @NotBlank @Size(max = 120)  String service,
        @Size(max = 120)            String host,
        @NotBlank @Size(max = 300)  String title,
        @NotBlank @Size(max = 8000) String body,
        @Size(max = 20)             String severity,
                                    Instant occurredAt
) {}
