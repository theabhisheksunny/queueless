package com.queueless.security;

import com.queueless.entity.SubscriptionSeat;
import com.queueless.entity.SubscriptionStatus;
import com.queueless.entity.User;
import com.queueless.entity.UserRole;
import com.queueless.repository.SubscriptionSeatRepository;
import com.queueless.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final CustomUserDetailsService userDetailsService;
    private final UserRepository userRepository;
    private final SubscriptionSeatRepository seatRepository;
    private final com.queueless.service.EntitlementService entitlementService;
    private final String adminKey;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider,
                                   CustomUserDetailsService userDetailsService,
                                   UserRepository userRepository,
                                   SubscriptionSeatRepository seatRepository,
                                   com.queueless.service.EntitlementService entitlementService,
                                   @Value("${app.admin.key:admin-secret-queueless-2026}") String adminKey) {
        this.tokenProvider = tokenProvider;
        this.userDetailsService = userDetailsService;
        this.userRepository = userRepository;
        this.seatRepository = seatRepository;
        this.entitlementService = entitlementService;
        this.adminKey = adminKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String jwt = getJwtFromRequest(request);

            if (StringUtils.hasText(jwt) && tokenProvider.validateToken(jwt)) {
                String username = tokenProvider.getUsernameFromToken(jwt);
                Date issuedAt = tokenProvider.getIssuedAt(jwt);

                // Session revocation check: verify if credentials were reset after token issue
                Optional<User> userOpt = userRepository.findByIdentifier(username);
                if (userOpt.isPresent()) {
                    User user = userOpt.get();
                    if (user.isRevoked()) {
                        logger.info("Session rejected for " + username + ": User account has been revoked.");
                        filterChain.doFilter(request, response);
                        return;
                    }

                    if (user.getRole() == UserRole.ROLE_STAFF) {
                        SubscriptionSeat seat = seatRepository.findByAssignedUserIdAndStatus(user.getId(), SubscriptionStatus.ACTIVE).orElse(null);
                        LocalDate today = LocalDate.now();
                        if (seat != null) {
                            if (seat.getEndDate() == null || today.isAfter(seat.getEndDate())) {
                                logger.info("Session rejected for " + username + ": Staff seat subscription has expired.");
                                filterChain.doFilter(request, response);
                                return;
                            }
                        } else {
                            // Staff is on the 1 included seat covered by owner's main subscription
                            if (user.getOwner() == null || !entitlementService.hasActiveOwnerEntitlement(user.getOwner().getId())) {
                                logger.info("Session rejected for " + username + ": Owner main subscription has expired.");
                                filterChain.doFilter(request, response);
                                return;
                            }
                        }
                    }

                    // Single active device session enforcement
                    String tokenSid = tokenProvider.getSessionIdFromToken(jwt);
                    if (tokenSid != null) {
                        if (user.getActiveSessionId() == null || !tokenSid.equals(user.getActiveSessionId())) {
                            logger.info("Session revoked for " + username + ": Active device session has changed, expired, or been reset.");
                            filterChain.doFilter(request, response);
                            return;
                        }
                    }

                    if (user.getCredentialsUpdatedAt() != null && issuedAt != null) {
                        long credSeconds = user.getCredentialsUpdatedAt().getEpochSecond();
                        long tokenSeconds = issuedAt.getTime() / 1000;
                        if (tokenSeconds < credSeconds) {
                            logger.info("Session revoked for " + username + ": PIN reset, logout, or device revocation occurred after token was issued.");
                            filterChain.doFilter(request, response);
                            return;
                        }
                    }
                }

                UserDetails userDetails = userDetailsService.loadUserByUsername(username);

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authentication);
            }

            // Restricted service identity for automated operational tasks via X-Admin-Key (ROLE_ADMIN only, NOT ROLE_SUPER_ADMIN)
            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                String adminKeyHeader = request.getHeader("X-Admin-Key");
                if (adminKeyHeader != null && !adminKeyHeader.isBlank() &&
                        MessageDigest.isEqual(adminKeyHeader.getBytes(StandardCharsets.UTF_8), adminKey.getBytes(StandardCharsets.UTF_8))) {
                    UserDetails serviceIdentity = new org.springframework.security.core.userdetails.User(
                            "service:automation",
                            "",
                            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
                    );
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(serviceIdentity, null, serviceIdentity.getAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        } catch (Exception ex) {
            logger.error("Could not set user authentication in security context", ex);
        }

        filterChain.doFilter(request, response);
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        // Also allow passing token as query param for SSE connections where headers cannot be easily set in browser EventSource
        String tokenParam = request.getParameter("token");
        if (StringUtils.hasText(tokenParam)) {
            return tokenParam;
        }
        return null;
    }
}
