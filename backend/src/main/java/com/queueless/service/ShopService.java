package com.queueless.service;

import com.queueless.dto.ShopSettingsRequest;
import com.queueless.entity.Shop;
import com.queueless.repository.ShopRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShopService {

    private final ShopRepository shopRepository;

    public ShopService(ShopRepository shopRepository) {
        this.shopRepository = shopRepository;
    }

    @Transactional(readOnly = true)
    public Shop getShop(Long shopId) {
        return shopRepository.findById(shopId)
                .orElseThrow(() -> new IllegalArgumentException("Shop not found with ID: " + shopId));
    }

    @Transactional
    public Shop updateShopSettings(Long shopId, ShopSettingsRequest request) {
        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new IllegalArgumentException("Shop not found with ID: " + shopId));

        if (request.getName() != null && !request.getName().isBlank()) {
            shop.setName(request.getName().trim());
        }
        if (request.getCategory() != null && !request.getCategory().isBlank()) {
            shop.setCategory(request.getCategory().trim());
        }
        if (request.getAvgServiceDurationMinutes() != null && request.getAvgServiceDurationMinutes() > 0) {
            shop.setAvgServiceDurationMinutes(request.getAvgServiceDurationMinutes());
        }

        return shopRepository.save(shop);
    }
}
