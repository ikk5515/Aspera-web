package com.aspera.web.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProductionDatabaseTlsValidatorTest {

    @Test
    void rejectsUnsupportedNonRootContextPath() {
        assertDoesNotThrow(() -> ProductionDatabaseTlsValidator.validateContextPath(null));
        assertDoesNotThrow(() -> ProductionDatabaseTlsValidator.validateContextPath("/"));
        assertThrows(IllegalStateException.class,
                () -> ProductionDatabaseTlsValidator.validateContextPath("/aspera"));
    }

    @Test
    void acceptsRemotePostgresqlWithVerifyFull() {
        assertDoesNotThrow(() -> ProductionDatabaseTlsValidator.validateProductionUrl(
                "jdbc:postgresql://db.example.internal:5432/app?sslmode=verify-full"));
    }

    @Test
    void acceptsLoopbackPostgresqlWithoutTlsOverride() {
        assertDoesNotThrow(() -> ProductionDatabaseTlsValidator.validateProductionUrl(
                "jdbc:postgresql://127.0.0.1:5432/app"));
        assertDoesNotThrow(() -> ProductionDatabaseTlsValidator.validateProductionUrl(
                "jdbc:postgresql://[::1]:5432/app"));
    }

    @Test
    void rejectsRemotePostgresqlWithoutVerifyFull() {
        assertThrows(IllegalStateException.class, () -> ProductionDatabaseTlsValidator.validateProductionUrl(
                "jdbc:postgresql://db.example.internal:5432/app"));
        assertThrows(IllegalStateException.class, () -> ProductionDatabaseTlsValidator.validateProductionUrl(
                "jdbc:postgresql://db.example.internal:5432/app?sslmode=require"));
    }

    @Test
    void rejectsDuplicateSslModeDowngrade() {
        assertThrows(IllegalStateException.class, () -> ProductionDatabaseTlsValidator.validateProductionUrl(
                "jdbc:postgresql://db.example.internal:5432/app?sslmode=verify-full&sslmode=disable"));
        assertThrows(IllegalStateException.class, () -> ProductionDatabaseTlsValidator.validateProductionUrl(
                "jdbc:postgresql://db.example.internal:5432/app?sslmode=disable&sslmode=verify-full"));
    }

    @Test
    void rejectsSslModeCaseAndWhitespaceVariantsIgnoredByPgjdbc() {
        for (String query : java.util.List.of(
                "SSLMODE=verify-full",
                "sslMode=verify-full",
                " sslmode=verify-full",
                "sslmode =verify-full",
                "sslmode=VERIFY-FULL",
                "sslmode=verify-full ")) {
            assertThrows(IllegalStateException.class, () -> ProductionDatabaseTlsValidator.validateProductionUrl(
                    "jdbc:postgresql://db.example.internal:5432/app?" + query));
        }
    }

    @Test
    void rejectsMissingOrUnexpectedProductionDatasource() {
        assertThrows(IllegalStateException.class,
                () -> ProductionDatabaseTlsValidator.validateProductionUrl(""));
        assertThrows(IllegalStateException.class, () -> ProductionDatabaseTlsValidator.validateProductionUrl(
                "jdbc:mysql://db.example.internal/app?sslMode=VERIFY_IDENTITY"));
    }
}
