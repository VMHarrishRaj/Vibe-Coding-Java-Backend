package com.truckhire.modules.admin.service;

import com.truckhire.modules.admin.dto.AdminBookingChartResponse;
import com.truckhire.modules.admin.dto.AdminBookingChartResponse.MonthlyBookings;
import com.truckhire.modules.admin.dto.AdminDashboardStatsResponse;
import com.truckhire.modules.admin.dto.AdminRevenueChartResponse;
import com.truckhire.modules.admin.dto.AdminRevenueChartResponse.MonthlyRevenue;
import com.truckhire.modules.booking.entity.BookingStatus;
import com.truckhire.modules.user.entity.UserStatus;
import com.truckhire.modules.booking.repository.BookingRepository;
import com.truckhire.modules.payment.repository.PaymentTransactionRepository;
import com.truckhire.modules.truck.entity.TruckStatus;
import com.truckhire.modules.truck.repository.TruckRepository;
import com.truckhire.modules.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private final BookingRepository bookingRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final TruckRepository truckRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public AdminDashboardStatsResponse getStats() {
        // ── KPI cards ──
        // Single source of truth: sum PAID CHARGE + MILEAGE_TOPUP transactions — matches invoice screen exactly.
        BigDecimal totalRevenue = transactionRepository.sumTotalPlatformRevenue();
        long activeBookings = bookingRepository.countByStatusAndDeletedAtIsNull(BookingStatus.ACTIVE);
        // All non-deleted users across all roles — matches totalElements returned by GET /admin/users (no filter).
        long totalClients = userRepository.countByDeletedAtIsNull();
        long activeOwnerCount = userRepository.countByRole_NameAndStatusAndDeletedAtIsNull("OWNER", UserStatus.ACTIVE);

        // ── Booking Status widget ──
        long ongoingBookings   = bookingRepository.countByStatusAndDeletedAtIsNull(BookingStatus.ACTIVE);
        long completedBookings = bookingRepository.countByStatusAndDeletedAtIsNull(BookingStatus.COMPLETED);
        // Upcoming = PENDING + AWAITING_APPROVAL (payment captured, owner deciding) + CONFIRMED (accepted, not yet started)
        long upcomingBookings  = bookingRepository.countByStatusAndDeletedAtIsNull(BookingStatus.PENDING)
                + bookingRepository.countByStatusAndDeletedAtIsNull(BookingStatus.AWAITING_APPROVAL)
                + bookingRepository.countByStatusAndDeletedAtIsNull(BookingStatus.CONFIRMED);
        long rejectedBookings  = bookingRepository.countByStatusAndDeletedAtIsNull(BookingStatus.REJECTED);
        long cancelledBookings = bookingRepository.countByStatusAndDeletedAtIsNull(BookingStatus.CANCELLED);
        // totalBookings = sum of all slices — guarantees pie chart total matches KPI card
        long totalBookings = ongoingBookings + completedBookings + upcomingBookings + rejectedBookings + cancelledBookings;

        // ── Vehicle Availability widget ──
        long rentedVehicles = truckRepository.countRentedTrucks();
        // Available = APPROVED trucks minus those currently rented out
        long approvedVehicles = truckRepository.countByStatusAndDeletedAtIsNull(TruckStatus.APPROVED);
        long availableVehicles = approvedVehicles - rentedVehicles;
        // Not Available = INACTIVE + PENDING_APPROVAL + REJECTED
        // REJECTED included: owner may resolve and re-submit in future; keeps pie total == totalVehicles
        long notAvailableVehicles = truckRepository.countByStatusAndDeletedAtIsNull(TruckStatus.INACTIVE)
                + truckRepository.countByStatusAndDeletedAtIsNull(TruckStatus.PENDING_APPROVAL)
                + truckRepository.countByStatusAndDeletedAtIsNull(TruckStatus.REJECTED);
        // totalVehicles = sum of pie slices — single source of truth, no stale countByDeletedAtIsNull()
        long totalVehicles = availableVehicles + rentedVehicles + notAvailableVehicles;

        return AdminDashboardStatsResponse.builder()
                .totalRevenue(totalRevenue)
                .activeBookings(activeBookings)
                .totalVehicles(totalVehicles)
                .totalClients(totalClients)
                .activeOwnerCount(activeOwnerCount)
                .totalBookings(totalBookings)
                .ongoingBookings(ongoingBookings)
                .completedBookings(completedBookings)
                .upcomingBookings(upcomingBookings)
                .rejectedBookings(rejectedBookings)
                .cancelledBookings(cancelledBookings)
                .availableVehicles(availableVehicles)
                .rentedVehicles(rentedVehicles)
                .notAvailableVehicles(notAvailableVehicles)
                .build();
    }

    @Transactional(readOnly = true)
    public AdminRevenueChartResponse getRevenueChart() {
        List<Object[]> rows = bookingRepository.sumRevenueGroupedByMonth();
        List<MonthlyRevenue> data = rows.stream()
                .map(row -> MonthlyRevenue.builder()
                        .month((String) row[0])
                        .revenue((BigDecimal) row[1])
                        .build())
                .toList();
        return AdminRevenueChartResponse.builder().data(data).build();
    }

    @Transactional(readOnly = true)
    public AdminBookingChartResponse getBookingChart() {
        List<Object[]> rows = bookingRepository.countBookingsGroupedByMonth();
        List<MonthlyBookings> data = rows.stream()
                .map(row -> MonthlyBookings.builder()
                        .month((String) row[0])
                        .bookingCount(((Number) row[1]).longValue())
                        .build())
                .toList();
        return AdminBookingChartResponse.builder().data(data).build();
    }
}
