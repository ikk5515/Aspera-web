package com.aspera.web.config;

import java.util.Locale;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

public final class ProductionDatabaseTlsValidator implements EnvironmentPostProcessor {

    private static final String POSTGRESQL_PREFIX = "jdbc:postgresql:";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        validateContextPath(environment.getProperty("server.servlet.context-path"));
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            validateProductionUrl(environment.getProperty("spring.datasource.url"));
        }
    }

    static void validateContextPath(String contextPath) {
        if (contextPath != null && !contextPath.isBlank() && !contextPath.equals("/")) {
            throw new IllegalStateException(
                    "Non-root server.servlet.context-path values are not supported by this application.");
        }
    }

    static void validateProductionUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalStateException("A PostgreSQL datasource URL is required in the prod profile.");
        }
        if (!jdbcUrl.regionMatches(true, 0, POSTGRESQL_PREFIX, 0, POSTGRESQL_PREFIX.length())) {
            throw new IllegalStateException("The prod profile only supports PostgreSQL datasources.");
        }
        if (!usesOnlyLoopbackHosts(jdbcUrl) && !usesOnlyVerifyFullSslModes(jdbcUrl)) {
            throw new IllegalStateException(
                    "Remote PostgreSQL connections in the prod profile require sslmode=verify-full.");
        }
    }

    private static boolean usesOnlyLoopbackHosts(String jdbcUrl) {
        String remainder = jdbcUrl.substring(POSTGRESQL_PREFIX.length());
        if (!remainder.startsWith("//")) {
            return true;
        }

        int authorityEnd = remainder.length();
        for (char delimiter : new char[] { '/', '?' }) {
            int index = remainder.indexOf(delimiter, 2);
            if (index >= 0 && index < authorityEnd) {
                authorityEnd = index;
            }
        }
        String authority = remainder.substring(2, authorityEnd);
        if (authority.isBlank()) {
            return false;
        }

        for (String hostPort : authority.split(",", -1)) {
            String candidate = hostPort.trim();
            int userInfoEnd = candidate.lastIndexOf('@');
            if (userInfoEnd >= 0) {
                candidate = candidate.substring(userInfoEnd + 1);
            }

            String host;
            if (candidate.startsWith("[")) {
                int closingBracket = candidate.indexOf(']');
                if (closingBracket < 0) {
                    return false;
                }
                host = candidate.substring(1, closingBracket);
            } else {
                int firstColon = candidate.indexOf(':');
                int lastColon = candidate.lastIndexOf(':');
                host = firstColon >= 0 && firstColon == lastColon
                        ? candidate.substring(0, firstColon)
                        : candidate;
            }

            String normalizedHost = host.toLowerCase(Locale.ROOT);
            if (!(normalizedHost.equals("localhost")
                    || normalizedHost.equals("127.0.0.1")
                    || normalizedHost.equals("::1")
                    || normalizedHost.equals("0:0:0:0:0:0:0:1"))) {
                return false;
            }
        }
        return true;
    }

    private static boolean usesOnlyVerifyFullSslModes(String jdbcUrl) {
        int queryIndex = jdbcUrl.indexOf('?');
        if (queryIndex < 0 || queryIndex == jdbcUrl.length() - 1) {
            return false;
        }

        boolean sslModeFound = false;
        String query = jdbcUrl.substring(queryIndex + 1);
        for (String parameter : query.split("&", -1)) {
            int separator = parameter.indexOf('=');
            if (separator < 0) {
                continue;
            }
            String key = parameter.substring(0, separator);
            if (!key.equals("sslmode")) {
                if (key.trim().equalsIgnoreCase("sslmode")) {
                    return false;
                }
                continue;
            }
            sslModeFound = true;
            String value = parameter.substring(separator + 1);
            if (!value.equals("verify-full")) {
                return false;
            }
        }
        return sslModeFound;
    }
}
