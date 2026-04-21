package com.truckhire.modules.truck.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response for POST /trucks/{id}/blocked-dates/toggle
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToggleBlockedDateResponse {

    private String date;    // YYYY-MM-DD
    private String action;  // "BLOCKED" | "UNBLOCKED"
    private String color;   // "red" | "green" — new state after toggle
}
