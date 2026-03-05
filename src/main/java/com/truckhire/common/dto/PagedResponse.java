package com.truckhire.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Paginated response wrapper for list endpoints.
 *
 * Mobile apps cannot handle unbounded result sets.
 * Every list endpoint (trucks, bookings, users, etc.) returns this.
 *
 * Example response:
 * {
 *   "content": [...],
 *   "pageNumber": 0,
 *   "pageSize": 20,
 *   "totalElements": 143,
 *   "totalPages": 8,
 *   "last": false
 * }
 *
 * @param <T>  The type of items in the list
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PagedResponse<T> {

    private List<T> content;
    private int pageNumber;
    private int pageSize;
    private long totalElements;
    private int totalPages;
    private boolean last;
}
