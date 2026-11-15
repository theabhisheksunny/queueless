package com.queueless.service;

import com.queueless.dto.AuthRequest;
import com.queueless.dto.AuthResponse;
import com.queueless.dto.RegisterRequest;
import com.queueless.entity.*;
import com.queueless.repository.ShopRepository;
import com.queueless.repository.SubscriptionRepository;
import com.queueless.repository.UserRepository;
import com.queueless.security.JwtTokenProvider;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final ShopRepository shopRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;

    public AuthService(UserRepository userRepository,
                       ShopRepository shopRepository,
                       SubscriptionRepository subscriptionRepository,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       JwtTokenProvider tokenProvider) {
        this.userRepository = userRepository;
        this.shopRepository = shopRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.tokenProvider = tokenProvider;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail().trim().toLowerCase())) {
            throw new IllegalArgumentException("An account with this email already exists.");
        }

        User user = new User(
                request.getEmail().trim().toLowerCase(),
                passwordEncoder.encode(request.getPassword()),
                request.getFullName().trim(),
                request.getPhone() != null ? request.getPhone().trim() : null,
                UserRole.ROLE_OWNER
        );
        user = userRepository.save(user);

        Shop shop = new Shop(
                user,
                request.getShopName().trim(),
                request.getCategory() != null ? request.getCategory().trim() : "General",
                request.getAvgServiceDurationMinutes() != null ? request.getAvgServiceDurationMinutes() : 15
        );
        shop = shopRepository.save(shop);

        // Grant 30 days initial trial subscription
        LocalDate today = LocalDate.now();
        LocalDate expiry = today.plusDays(30);
        Subscription trial = new Subscription(
                shop,
                SubscriptionPlan.TRIAL,
                SubscriptionStatus.ACTIVE,
                today,
                expiry,
                BigDecimal.ZERO
        );
        trial.setNotes("Complimentary 30-day initial trial");
        subscriptionRepository.save(trial);

        String token = tokenProvider.generateTokenForUsername(user.getEmail());

        return new AuthResponse(
                token,
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                shop.getId(),
                shop.getName(),
                shop.getCategory(),
                true,
                30
        );
    }

    @Transactional(readOnly = true)
    public AuthResponse login(AuthRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, request.getPassword())
        );

        SecurityContextHolder.getContext().setAuthentication(authentication);
        String token = tokenProvider.generateToken(authentication);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Shop shop = shopRepository.findByOwnerId(user.getId()).orElse(null);

        Long shopId = shop != null ? shop.getId() : null;
        String shopName = shop != null ? shop.getName() : null;
        String category = shop != null ? shop.getCategory() : null;

        boolean activeSubscription = false;
        long daysRemaining = 0;

        if (shop != null) {
            Subscription sub = subscriptionRepository.findTopByShopIdOrderByEndDateDesc(shop.getId()).orElse(null);
            if (sub != null && sub.getStatus() == SubscriptionStatus.ACTIVE && sub.getEndDate() != null) {
                activeSubscription = !sub.getEndDate().isBefore(LocalDate.now());
                daysRemaining = Math.max(0, ChronoUnit.DAYS.between(LocalDate.now(), sub.getEndDate()));
            }
        }

        return new AuthResponse(
                token,
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                shopId,
                shopName,
                category,
                activeSubscription,
                daysRemaining
        );
    }
}
