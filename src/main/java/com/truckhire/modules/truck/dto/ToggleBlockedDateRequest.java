package com.truckhire.modules.truck.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Request for POST /trucks/{id}/blocked-dates/toggle
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToggleBlockedDateRequest {

    @NotNull(message = "Date is required")
    private LocalDate date;
}
