package com.truckhire.modules.truck.repository;

import com.truckhire.modules.truck.entity.TruckBlockedDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TruckBlockedDateRepository extends JpaRepository<TruckBlockedDate, UUID> {

    Optional<TruckBlockedDate> findByTruckIdAndBlockedDate(UUID truckId, LocalDate blockedDate);

    // Batch: all blocked dates for a set of trucks within a date range — one query for fleet availability
    @Query("SELECT tbd.truck.id, tbd.blockedDate FROM TruckBlockedDate tbd WHERE tbd.truck.id IN :truckIds AND tbd.blockedDate BETWEEN :from AND :to")
    List<Object[]> findBlockedDatesByTruckIds(
            @Param("truckIds") List<UUID> truckIds,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    // All blocked dates for a truck within a calendar month
    @Query("SELECT tbd.blockedDate FROM TruckBlockedDate tbd WHERE tbd.truck.id = :truckId AND tbd.blockedDate BETWEEN :from AND :to")
    List<LocalDate> findBlockedDatesBetween(
            @Param("truckId") UUID truckId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    // Used by booking conflict check — does any blocked date fall in the requested range?
    @Query("SELECT COUNT(tbd) > 0 FROM TruckBlockedDate tbd WHERE tbd.truck.id = :truckId AND tbd.blockedDate BETWEEN :from AND :to")
    boolean existsBlockedDateInRange(
            @Param("truckId") UUID truckId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    // Bulk delete — used by batch availability save
    @Modifying
    @Query("DELETE FROM TruckBlockedDate tbd WHERE tbd.truck.id = :truckId AND tbd.blockedDate IN :dates")
    void deleteByTruckIdAndBlockedDateIn(
            @Param("truckId") UUID truckId,
            @Param("dates") List<LocalDate> dates);
}
