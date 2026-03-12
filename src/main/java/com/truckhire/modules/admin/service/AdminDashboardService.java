package com.truckhire.modules.admin.service;

import com.truckhire.modules.admin.dto.AdminBookingChartResponse;
import com.truckhire.modules.admin.dto.AdminBookingChartResponse.MonthlyBookings;
import com.truckhire.modules.admin.dto.AdminDashboardStatsResponse;
import com.truckhire.modules.admin.dto.AdminRevenueChartResponse;
import com.truckhire.modules.admin.dto.AdminRevenueChartResponse.MonthlyRevenue;
import com.truckhire.modules.booking.entity.BookingStatus;
import com.truckhire.modules.booking.repository.BookingRepository;
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
    private final TruckRepository truckRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public AdminDashboardStatsResponse getStats() {
        BigDecimal totalRevenue = bookingRepository.sumTotalRevenueCompleted();
        long activeBookings = bookingRepository.countByStatusAndDeletedAtIsNull(BookingStatus.ACTIVE);
        long totalVehicles = truckRepository.countByDeletedAtIsNull();
        long totalClients = userRepository.countByRole_NameAndDeletedAtIsNull("RENTER");

        return AdminDashboardStatsResponse.builder()
                .totalRevenue(totalRevenue)
                .activeBookings(activeBookings)
                .totalVehicles(totalVehicles)
                .totalClients(totalClients)
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
