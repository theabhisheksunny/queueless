package com.queueless.service;

import com.queueless.entity.Shop;
import com.queueless.entity.ShopMembership;
import com.queueless.entity.User;
import com.queueless.entity.UserRole;
import com.queueless.repository.ShopMembershipRepository;
import com.queueless.repository.ShopRepository;
import com.queueless.repository.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.security.Principal;
import java.util.List;

/**
 * Centralized service to resolve authenticated User and Shop entities from a security Principal.
 * Supports multi-business, multi-branch, and staff authorization boundaries.
 */
@Service
public class AuthenticatedShopResolver {

    private final UserRepository userRepository;
    private final ShopRepository shopRepository;
    private final ShopMembershipRepository membershipRepository;

    public AuthenticatedShopResolver(UserRepository userRepository,
                                   ShopRepository shopRepository,
                                   ShopMembershipRepository membershipRepository) {
        this.userRepository = userRepository;
        this.shopRepository = shopRepository;
        this.membershipRepository = membershipRepository;
    }

    /**
     * Resolves the authenticated User entity from the provided Principal.
     */
    public User resolveUser(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new IllegalStateException("Authentication required");
        }
        return userRepository.findByIdentifier(principal.getName())
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + principal.getName()));
    }

    /**
     * Resolves the primary/active Shop for the authenticated user.
     */
    public Shop resolveShop(Principal principal) {
        return resolveShop(principal, null);
    }

    /**
     * Resolves and verifies authorization for a specific shopId, falling back to primary/assigned shop if shopId is null.
     */
    public Shop resolveShop(Principal principal, Long shopId) {
        User user = resolveUser(principal);
        return resolveShop(user, shopId);
    }

    /**
     * Resolves and verifies authorization for a specific shopId for a managed User entity.
     */
    public Shop resolveShop(User user, Long shopId) {
        if (shopId != null) {
            Shop shop = shopRepository.findById(shopId)
                    .orElseThrow(() -> new IllegalArgumentException("Shop not found with ID: " + shopId));

            if (user.getRole() == UserRole.ROLE_OWNER) {
                if (shop.getOwner() == null || !shop.getOwner().getId().equals(user.getId())) {
                    throw new AccessDeniedException("Access denied: You do not own this physical shop or branch.");
                }
                return shop;
            }

            if (user.getRole() == UserRole.ROLE_STAFF) {
                boolean hasAccess = membershipRepository.existsByUserIdAndShopIdAndStatus(user.getId(), shopId, "ACTIVE");
                if (!hasAccess) {
                    throw new AccessDeniedException("Access denied: You are not an authorized staff member for this branch.");
                }
                return shop;
            }

            throw new AccessDeniedException("Access denied: Super Admin accounts cannot impersonate or directly access merchant shops.");
        }

        // shopId is null -> resolve default / primary shop
        if (user.getRole() == UserRole.ROLE_OWNER) {
            // Find primary shop first, else first created shop
            Shop primary = shopRepository.findFirstByOwnerIdOrderByIsPrimaryDescCreatedAtAsc(user.getId()).orElse(null);
            if (primary != null) {
                return primary;
            }
            return shopRepository.findByOwnerId(user.getId())
                    .orElseThrow(() -> new IllegalArgumentException("No shop associated with owner: " + (user.getPhone() != null ? user.getPhone() : user.getEmail())));
        }

        if (user.getRole() == UserRole.ROLE_STAFF) {
            List<ShopMembership> memberships = membershipRepository.findAllByUserIdAndStatus(user.getId(), "ACTIVE");
            if (memberships.isEmpty()) {
                throw new AccessDeniedException("No authorized branches currently assigned to staff account.");
            }
            return memberships.get(0).getShop();
        }

        throw new AccessDeniedException("Access denied: Super Admin accounts do not have an associated merchant shop.");
    }

    /**
     * Enforces that the caller is an OWNER.
     * Prevents Super Admin from impersonating shop owners on operational merchant endpoints.
     */
    public void requireOwnerRole(User user) {
        if (user.getRole() != UserRole.ROLE_OWNER) {
            throw new AccessDeniedException("Owner privileges required to perform this action.");
        }
    }
}
