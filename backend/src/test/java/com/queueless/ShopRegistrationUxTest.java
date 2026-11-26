package com.queueless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueless.dto.AuthResponse;
import com.queueless.dto.QueueDashboardResponse;
import com.queueless.dto.RegisterRequest;
import com.queueless.entity.BusinessCategoryConstants;
import com.queueless.entity.Shop;
import com.queueless.entity.User;
import com.queueless.entity.UserRole;
import com.queueless.repository.ShopRepository;
import com.queueless.repository.UserRepository;
import com.queueless.service.AuthService;
import com.queueless.service.QueueService;
import com.queueless.service.ShopService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class ShopRegistrationUxTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private ShopService shopService;

    @Autowired
    private QueueService queueService;

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("1. Successful Registration: Sharma General Store with Retail & Shopping and Grocery Store persists businessType")
    void testSuccessfulRegistrationWithBusinessTypeAndBroadCategory() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setFullName("Ramesh Sharma");
        req.setPhone("9811223344");
        req.setPin("123456");
        req.setConfirmPin("123456");
        req.setShopName("Sharma General Store");
        req.setCategory("Retail & Shopping");
        req.setBusinessType("Grocery Store");
        req.setAvgServiceDurationMinutes(15);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.shopName").value("Sharma General Store"))
                .andExpect(jsonPath("$.category").value("Retail & Shopping"))
                .andExpect(jsonPath("$.businessType").value("Grocery Store"));

        Shop savedShop = shopRepository.findAll().stream()
                .filter(s -> "Sharma General Store".equals(s.getName()))
                .findFirst()
                .orElseThrow();

        assertEquals("Grocery Store", savedShop.getBusinessType());
        assertEquals("Retail & Shopping", savedShop.getCategory());
        assertNotNull(savedShop.getPublicMerchantCode());
        assertTrue(savedShop.getPublicMerchantCode().startsWith("pm_"));

        QueueDashboardResponse dashboard = queueService.getDashboard(savedShop.getId());
        assertEquals("Grocery Store", dashboard.getBusinessType());
        assertEquals("Retail & Shopping", dashboard.getCategory());
    }

    @Test
    @DisplayName("2. Empty, whitespace-only, or explicit null Business Type is rejected with 400 Bad Request")
    void testBlankBusinessTypeRejected() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setFullName("Suresh Verma");
        req.setPhone("9876543211");
        req.setPin("123456");
        req.setConfirmPin("123456");
        req.setShopName("Verma Medical Store");
        req.setCategory("Healthcare & Medical");

        // 2a. Empty string
        req.setBusinessType("");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());

        // 2b. Whitespace-only string
        req.setBusinessType("   ");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());

        // 2c. Explicit null
        req.setBusinessType(null);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("2b. Completely omitted Business Type property in JSON is rejected with 400 Bad Request")
    void testOmittedBusinessTypePropertyRejected() throws Exception {
        // Construct JSON payload without businessType property entirely
        Map<String, Object> payload = new HashMap<>();
        payload.put("fullName", "Suresh Verma");
        payload.put("phone", "9876543212");
        payload.put("pin", "123456");
        payload.put("confirmPin", "123456");
        payload.put("shopName", "Verma Medical Store");
        payload.put("category", "Healthcare & Medical");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("3. All 10 Broad Categories are valid in BusinessCategoryConstants")
    void testAllTenBroadCategoriesValid() {
        String[] categories = {
                "Salon & Personal Care",
                "Healthcare & Medical",
                "Restaurant & Food",
                "Retail & Shopping",
                "Repair & Maintenance",
                "Automobile Services",
                "Education & Coaching",
                "Professional Services",
                "Home & Local Services",
                "Other Business"
        };

        assertEquals(10, BusinessCategoryConstants.BROAD_CATEGORIES.size());
        for (String cat : categories) {
            assertTrue(BusinessCategoryConstants.isValidCategory(cat), "Category must be valid: " + cat);
        }
    }

    @Test
    @DisplayName("4. Historical Categories remain valid for existing shops and backward compatibility")
    void testHistoricalCategoriesRemainValid() {
        String[] historical = {
                "Barber Shop",
                "Salon & Spa",
                "Clinic & Doctor",
                "Small Hospital",
                "Restaurant / Cafe",
                "Repair Shop",
                "Automobile Service",
                "Other Local Business",
                "General"
        };

        for (String cat : historical) {
            assertTrue(BusinessCategoryConstants.isValidCategory(cat), "Historical category must be valid: " + cat);
        }
    }

    @Test
    @DisplayName("5. Existing legacy shops without businessType (null) remain readable and operable")
    void testLegacyShopWithoutBusinessTypeReadable() {
        User owner = new User("legacy@queueless.com", "pass123", "Legacy Owner", "9876543299", UserRole.ROLE_OWNER);
        owner = userRepository.save(owner);

        Shop legacyShop = new Shop(owner, "Legacy Barber Shop", "Barber Shop", 15);
        legacyShop.setBusinessType(null); // Existing legacy row without businessType
        legacyShop = shopService.saveShopWithUniqueCodeRetry(legacyShop);

        Shop fetched = shopRepository.findById(legacyShop.getId()).orElseThrow();
        assertNull(fetched.getBusinessType());
        assertEquals("Barber Shop", fetched.getCategory());

        QueueDashboardResponse dashboard = queueService.getDashboard(legacyShop.getId());
        assertNotNull(dashboard);
        assertNull(dashboard.getBusinessType());
        assertEquals("Barber Shop", dashboard.getCategory());
    }
}
