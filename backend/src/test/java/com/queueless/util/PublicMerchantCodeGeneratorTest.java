package com.queueless.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class PublicMerchantCodeGeneratorTest {

    @Test
    @DisplayName("Format: Generated public merchant codes start with 'pm_' and are exactly 19 characters")
    void testFormatAndLength() {
        for (int i = 0; i < 100; i++) {
            String code = PublicMerchantCodeGenerator.generate();
            assertNotNull(code);
            assertEquals(19, code.length(), "Code length must be 3 (prefix 'pm_') + 16 (Base62 chars) = 19");
            assertTrue(code.startsWith("pm_"), "Code must start with 'pm_' prefix");
            assertTrue(PublicMerchantCodeGenerator.isValid(code), "Generated code must pass validation");
        }
    }

    @Test
    @DisplayName("Entropy & Uniqueness: 10,000 sequentially generated codes have zero collisions")
    void testUniquenessAcrossTenThousandGenerations() {
        Set<String> generated = new HashSet<>(10000);
        for (int i = 0; i < 10000; i++) {
            String code = PublicMerchantCodeGenerator.generate();
            assertTrue(generated.add(code), "Collision detected at index " + i + ": " + code);
        }
        assertEquals(10000, generated.size());
    }

    @Test
    @DisplayName("Validation: Rejects invalid prefixes, lengths, non-Base62 characters, and nulls")
    void testValidationEdgeCases() {
        assertFalse(PublicMerchantCodeGenerator.isValid(null));
        assertFalse(PublicMerchantCodeGenerator.isValid(""));
        assertFalse(PublicMerchantCodeGenerator.isValid("pm_"));
        assertFalse(PublicMerchantCodeGenerator.isValid("pm_short123"));
        assertFalse(PublicMerchantCodeGenerator.isValid("pm_toolongcharacters1234567890"));
        assertFalse(PublicMerchantCodeGenerator.isValid("sh_1234567890abcdef")); // Wrong prefix
        assertFalse(PublicMerchantCodeGenerator.isValid("pm_1234567890abcd!")); // Special char
        assertFalse(PublicMerchantCodeGenerator.isValid("pm_1234567890abcd-")); // Hyphen not in Base62
        assertFalse(PublicMerchantCodeGenerator.isValid("1234567890123456789")); // No prefix
        assertFalse(PublicMerchantCodeGenerator.isValid("1")); // Sequential ID

        // Valid code
        assertTrue(PublicMerchantCodeGenerator.isValid("pm_aB3dE5gH7jK9mN1p"));
    }
}
