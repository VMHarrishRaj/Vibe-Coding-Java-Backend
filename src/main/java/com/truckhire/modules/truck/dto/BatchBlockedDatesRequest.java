package com.truckhire.modules.truck.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * Request for POST /trucks/{id}/blocked-dates/batch
 * Frontend stages all day-toggle changes locally, then sends the full diff on Save.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchBlockedDatesRequest {

    @NotNull
    private List<LocalDate> datesToBlock;

    @NotNull
    private List<LocalDate> datesToUnblock;
}
