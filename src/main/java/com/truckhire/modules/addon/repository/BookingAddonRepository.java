package com.truckhire.modules.addon.repository;

import com.truckhire.modules.addon.entity.BookingAddon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BookingAddonRepository extends JpaRepository<BookingAddon, UUID> {

    // Load all addons for a booking (used in BookingResponse and GET /bookings/{id}/addons)
    List<BookingAddon> findByBookingId(UUID bookingId);
}
