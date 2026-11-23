package com.queueless.repository;

import com.queueless.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    Optional<User> findByPhone(String phone);
    boolean existsByPhone(String phone);
    java.util.List<User> findAllByOwnerIdOrderByCreatedAtAsc(Long ownerId);
    long countByOwnerIdAndRoleAndStatus(Long ownerId, com.queueless.entity.UserRole role, String status);
    Optional<User> findByIdAndOwnerId(Long id, Long ownerId);
    long countByRole(com.queueless.entity.UserRole role);
    java.util.List<User> findAllByRoleOrderByCreatedAtDesc(com.queueless.entity.UserRole role);
    org.springframework.data.domain.Page<User> findAllByRoleOrderByCreatedAtDesc(com.queueless.entity.UserRole role, org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query("SELECT u FROM User u WHERE u.role = :role AND (" +
            "LOWER(u.fullName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
            "u.phone LIKE CONCAT('%', :query, '%') OR " +
            "LOWER(u.email) LIKE LOWER(CONCAT('%', :query, '%')))")
    org.springframework.data.domain.Page<User> searchOwners(@org.springframework.data.repository.query.Param("role") com.queueless.entity.UserRole role,
                                                            @org.springframework.data.repository.query.Param("query") String query,
                                                            org.springframework.data.domain.Pageable pageable);

    java.util.List<User> findTop5ByRoleOrderByCreatedAtDesc(com.queueless.entity.UserRole role);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    default Optional<User> findByIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Optional.empty();
        }
        String trimmed = identifier.trim();
        Optional<User> byPhone = findByPhone(trimmed);
        if (byPhone.isPresent()) {
            return byPhone;
        }
        String digits = trimmed.replaceAll("[^0-9]", "");
        if (digits.length() >= 10) {
            String last10 = digits.substring(digits.length() - 10);
            Optional<User> byNormalized = findByPhone("+91" + last10);
            if (byNormalized.isPresent()) {
                return byNormalized;
            }
        }
        return findByEmail(trimmed.toLowerCase());
    }
}
