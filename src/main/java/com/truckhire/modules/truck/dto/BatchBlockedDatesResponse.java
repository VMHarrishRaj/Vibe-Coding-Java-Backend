package com.truckhire.modules.truck.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response for POST /trucks/{id}/blocked-dates/batch
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchBlockedDatesResponse {

    private List<String> blocked;         // dates successfully blocked
    private List<String> unblocked;       // dates successfully unblocked
    private List<String> skipped;         // dates skipped (past date or has active booking)
}
