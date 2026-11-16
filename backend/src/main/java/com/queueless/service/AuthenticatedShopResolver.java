package com.queueless.service;

import com.queueless.entity.Shop;
import com.queueless.entity.User;
import com.queueless.repository.ShopRepository;
import com.queueless.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.security.Principal;

/**
 * Centralized service to resolve authenticated User and Shop entities from a security Principal.
 * Eliminates redundant repository queries and ensures uniform authentication and error handling across controllers.
 */
@Service
public class AuthenticatedShopResolver {

    private final UserRepository userRepository;
    private final ShopRepository shopRepository;

    public AuthenticatedShopResolver(UserRepository userRepository, ShopRepository shopRepository) {
        this.userRepository = userRepository;
        this.shopRepository = shopRepository;
    }

    /**
     * Resolves the authenticated User entity from the provided Principal.
     *
     * @param principal Spring Security Principal
     * @return Managed User entity
     * @throws IllegalStateException    if principal is null or unauthenticated
     * @throws IllegalArgumentException if user does not exist in repository
     */
    public User resolveUser(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new IllegalStateException("Authentication required");
        }
        return userRepository.findByIdentifier(principal.getName())
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + principal.getName()));
    }

    /**
     * Resolves the authenticated User and their associated Shop entity from the provided Principal.
     *
     * @param principal Spring Security Principal
     * @return Managed Shop entity
     * @throws IllegalStateException    if principal is null or unauthenticated
     * @throws IllegalArgumentException if user or shop does not exist in repository
     */
    public Shop resolveShop(Principal principal) {
        User user = resolveUser(principal);
        return resolveShop(user);
    }

    /**
     * Resolves the Shop entity associated with a given User.
     *
     * @param user Authenticated user entity
     * @return Managed Shop entity
     * @throws IllegalArgumentException if no shop is associated with the user
     */
    public Shop resolveShop(User user) {
        return shopRepository.findByOwnerId(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("No shop associated with user: " + (user.getPhone() != null ? user.getPhone() : user.getEmail())));
    }
}
