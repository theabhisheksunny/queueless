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
