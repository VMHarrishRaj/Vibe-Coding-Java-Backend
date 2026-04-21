package com.truckhire.modules.truck.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One calendar day entry — returned in a list for GET /trucks/{id}/calendar
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalendarDayResponse {

    private String date;        // YYYY-MM-DD
    private String color;       // "green" | "red"
    private String type;        // "AVAILABLE" | "BLOCKED_BY_OWNER" | "BOOKED"
    private boolean interactive; // false when BOOKED — owner cannot toggle
}
