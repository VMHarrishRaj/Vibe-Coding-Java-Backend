package com.truckhire.modules.booking.repository;

import com.truckhire.modules.booking.entity.BookingStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BookingStatusHistoryRepository extends JpaRepository<BookingStatusHistory, Long> {

    @Query("""
            SELECT h FROM BookingStatusHistory h
            JOIN FETCH h.changedBy
            WHERE h.booking.id = :bookingId
            ORDER BY h.changedAt ASC
            """)
    List<BookingStatusHistory> findByBookingIdOrderByChangedAtAsc(@Param("bookingId") UUID bookingId);
}
