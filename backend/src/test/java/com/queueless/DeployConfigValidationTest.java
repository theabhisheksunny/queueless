package com.queueless;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Centralized Deployment Configuration & Provider Resolution Tests")
class DeployConfigValidationTest {

    private static final Path PROJECT_ROOT = Paths.get("..").toAbsolutePath().normalize();
    private static final Path VALIDATOR_SCRIPT = PROJECT_ROOT.resolve("deploy/scripts/validate_config.py");
    private static final Path RESOLVER_SCRIPT = PROJECT_ROOT.resolve("deploy/scripts/resolve_deployment_target.py");
    private static final Path STAGING_CONFIG = PROJECT_ROOT.resolve("deploy/config/staging.yaml");
    private static final Path PRODUCTION_CONFIG = PROJECT_ROOT.resolve("deploy/config/production.yaml");
    private static final Path LOCAL_CONFIG = PROJECT_ROOT.resolve("deploy/config/local.yaml");

    @Test
    @DisplayName("1. Deployment Validator Script: All standard environment configs pass strict validation")
    void testValidateAllConfigurationsPass() throws Exception {
        assertTrue(Files.exists(VALIDATOR_SCRIPT), "Validator script must exist at: " + VALIDATOR_SCRIPT);
        assertTrue(Files.exists(STAGING_CONFIG), "staging.yaml must exist at: " + STAGING_CONFIG);
        assertTrue(Files.exists(PRODUCTION_CONFIG), "production.yaml must exist at: " + PRODUCTION_CONFIG);
        assertTrue(Files.exists(LOCAL_CONFIG), "local.yaml must exist at: " + LOCAL_CONFIG);

        ProcessBuilder pb = new ProcessBuilder("python", VALIDATOR_SCRIPT.toString(), "--all");
        pb.directory(PROJECT_ROOT.toFile());
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        int exitCode = process.waitFor();
        assertEquals(0, exitCode, "validate_config.py --all must exit with 0. Output:\n" + output);
        assertTrue(output.toString().contains("ALL CONFIGURATIONS VALID"), "Output must confirm all configurations valid");
    }

    @Test
    @DisplayName("2. Provider Resolver: Staging resolves to Render with zero cloud secrets in YAML")
    void testResolveStagingTarget() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("python", RESOLVER_SCRIPT.toString(), STAGING_CONFIG.toString(), "--json");
        pb.directory(PROJECT_ROOT.toFile());
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        int exitCode = process.waitFor();
        assertEquals(0, exitCode, "resolve_deployment_target.py must exit with 0 for staging.yaml");

        String json = output.toString();
        assertTrue(json.contains("\"ACTIVE_PROVIDER\": \"render\""), "Staging active provider must be render");
        assertTrue(json.contains("\"BACKEND_URL\": \"https://queueless-backend-staging.onrender.com\""), "Staging backend URL must match Render service");
        assertTrue(json.contains("\"RENDER_DEPLOY_HOOK_SECRET_KEY\": \"RENDER_STAGING_DEPLOY_HOOK_URL\""), "Deploy hook must be secret reference");
        assertTrue(json.contains("\"DB_POOLER_PORT\": 5432"), "Database port must be 5432");
    }

    @Test
    @DisplayName("3. Provider Resolver: Production resolves to Render with zero cloud secrets in YAML")
    void testResolveProductionTarget() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("python", RESOLVER_SCRIPT.toString(), PRODUCTION_CONFIG.toString(), "--json");
        pb.directory(PROJECT_ROOT.toFile());
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        int exitCode = process.waitFor();
        assertEquals(0, exitCode, "resolve_deployment_target.py must exit with 0 for production.yaml");

        String json = output.toString();
        assertTrue(json.contains("\"ACTIVE_PROVIDER\": \"render\""), "Production active provider must be render");
        assertTrue(json.contains("\"BACKEND_URL\": \"https://queueless-backend.onrender.com\""), "Production backend URL must match Render service");
        assertTrue(json.contains("\"RENDER_DEPLOY_HOOK_SECRET_KEY\": \"RENDER_PROD_DEPLOY_HOOK_URL\""), "Deploy hook must be secret reference");
        assertTrue(json.contains("\"DB_POOLER_PORT\": 5432"), "Database port must be 5432");
    }

    @Test
    @DisplayName("4. Adversarial Check: Setting AWS as active_provider is strictly rejected in v1")
    void testAdversarialAwsActiveProviderRejected() throws Exception {
        Path tempYaml = Files.createTempFile("queueless-test-aws-", ".yaml");
        try {
            List<String> lines = Files.readAllLines(STAGING_CONFIG);
            StringBuilder modified = new StringBuilder();
            for (String line : lines) {
                if (line.startsWith("active_provider:")) {
                    modified.append("active_provider: \"aws\"\n");
                } else {
                    modified.append(line).append("\n");
                }
            }
            Files.writeString(tempYaml, modified.toString());

            ProcessBuilder pb = new ProcessBuilder("python", VALIDATOR_SCRIPT.toString(), tempYaml.toString());
            pb.directory(PROJECT_ROOT.toFile());
            Process process = pb.start();

            StringBuilder errOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    errOutput.append(line).append("\n");
                }
            }
            int exitCode = process.waitFor();
            assertNotEquals(0, exitCode, "Validation of active_provider=aws must fail with non-zero exit code");
            assertTrue(errOutput.toString().contains("AWS provider is stubbed for future extension and not yet operational in v1"),
                    "Error message must specify AWS is non-operational in v1");
        } finally {
            Files.deleteIfExists(tempYaml);
        }
    }

    @Test
    @DisplayName("5. Python Test Suites: Configuration and Routing/Rollback suites pass cleanly")
    void testPythonSuitesPass() throws Exception {
        Path testScript1 = PROJECT_ROOT.resolve("deploy/tests/test_config_validation.py");
        Path testScript2 = PROJECT_ROOT.resolve("deploy/tests/test_routing_and_health.py");
        assertTrue(Files.exists(testScript1), "test_config_validation.py must exist");
        assertTrue(Files.exists(testScript2), "test_routing_and_health.py must exist");

        for (Path script : List.of(testScript1, testScript2)) {
            ProcessBuilder pb = new ProcessBuilder("python", "-m", "unittest", script.toString());
            pb.directory(PROJECT_ROOT.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            int exitCode = process.waitFor();
            assertEquals(0, exitCode, "Python test suite " + script.getFileName() + " must pass. Output:\n" + output);
        }
    }
}
