package com.queueless.repository;

import com.queueless.entity.DeviceRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceRegistrationRepository extends JpaRepository<DeviceRegistration, Long> {
    Optional<DeviceRegistration> findByUserIdAndEnrollmentStatus(Long userId, String enrollmentStatus);
    Optional<DeviceRegistration> findByDeviceRegistrationId(String deviceRegistrationId);
    List<DeviceRegistration> findAllByUserId(Long userId);
    List<DeviceRegistration> findAllByUserIdOrderByCreatedAtDesc(Long userId);
    boolean existsByUserIdAndEnrollmentStatus(Long userId, String enrollmentStatus);
}
