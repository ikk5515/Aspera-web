package com.aspera.web.service;

import java.security.Principal;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

@Service
public class UserSessionService {

    private final SessionRegistry sessionRegistry;

    public UserSessionService(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    public int expireAllSessions(String username) {
        if (username == null || username.isBlank()) {
            return 0;
        }

        int expiredCount = 0;
        for (Object registeredPrincipal : sessionRegistry.getAllPrincipals()) {
            if (!username.equals(principalName(registeredPrincipal))) {
                continue;
            }
            for (SessionInformation session : sessionRegistry.getAllSessions(registeredPrincipal, false)) {
                session.expireNow();
                expiredCount++;
            }
        }
        return expiredCount;
    }

    private String principalName(Object registeredPrincipal) {
        if (registeredPrincipal instanceof UserDetails userDetails) {
            return userDetails.getUsername();
        }
        if (registeredPrincipal instanceof Principal principal) {
            return principal.getName();
        }
        return null;
    }
}
