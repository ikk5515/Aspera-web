package com.aspera.web.config;

import com.aspera.web.security.LoginAttemptService;
import com.aspera.web.security.LoginRateLimitFilter;
import com.aspera.web.security.JsonRequestSizeLimitFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, SessionRegistry sessionRegistry,
            LoginAttemptService loginAttemptService,
            AuthenticationSuccessHandler authenticationSuccessHandler) throws Exception {
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        RequestMatcher apiRequestMatcher = new OrRequestMatcher(
                paths.matcher("/admin/api/**"),
                paths.matcher(HttpMethod.PUT, "/admin/users/{userId}/permissions/{permissionId}"),
                paths.matcher("/files/dir-sizes"),
                paths.matcher("/files/transfer-spec"));
        AuthenticationEntryPoint apiAuthenticationEntryPoint = (request, response, exception) -> {
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"Authentication is required.\"}");
        };
        org.springframework.security.web.session.SessionInformationExpiredStrategy expiredSessionStrategy = event -> {
            if (apiRequestMatcher.matches(event.getRequest())) {
                event.getResponse().setStatus(401);
                event.getResponse().setContentType(MediaType.APPLICATION_JSON_VALUE);
                event.getResponse().getWriter().write("{\"error\":\"Session expired. Sign in again.\"}");
                return;
            }
            event.getResponse().sendRedirect(event.getRequest().getContextPath() + "/login?expired");
        };

        http
                .authorizeHttpRequests((requests) -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/error", "/css/**", "/js/**", "/images/**").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                // 폼 로그인 설정 (Data Flow Processing)
                // 1. 수신: 사용자가 '/login'에서 아이디/비밀번호를 제출하면(POST), Spring Security의 필터가 이를 가로챕니다.
                // 2. 검증: AuthenticationManager가 등록된
                // UserDetailsService(CustomUserDetailsService)를 호출하여
                // DB에 있는 사용자 정보와 입력된 비밀번호를 대조(verify)합니다.
                // 3. 결과 처리:
                // - 성공: successHandler()가 호출되어 사용자의 역할(Role)에 따른 리다이렉트 경로를 결정합니다.
                // - 실패: '/login?error'로 리다이렉트되어 로그인 페이지에 에러 메시지가 표시됩니다.
                .formLogin((form) -> form
                        .loginPage("/login")
                        .successHandler(authenticationSuccessHandler)
                        .failureHandler((request, response, exception) -> {
                            loginAttemptService.recordFailure(request.getRemoteAddr(),
                                    request.getParameter("username"));
                            response.sendRedirect(request.getContextPath() + "/login?error");
                        })
                        .permitAll())
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(apiAuthenticationEntryPoint, apiRequestMatcher)
                        .accessDeniedHandler((request, response, exception) -> {
                            if (apiRequestMatcher.matches(request)) {
                                response.setStatus(403);
                                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                                response.getWriter().write("{\"error\":\"Access denied.\"}");
                                return;
                            }
                            response.sendError(403);
                        }))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; "
                                        + "script-src 'self' https://d3gcli72yxqn2z.cloudfront.net; "
                                        + "script-src-attr 'none'; "
                                        + "style-src 'self'; "
                                        + "style-src-attr 'none'; "
                                        + "img-src 'self' data:; "
                                        + "connect-src 'self' https://d3gcli72yxqn2z.cloudfront.net "
                                        + "http://127.0.0.1:* https://127.0.0.1:* ws://127.0.0.1:* wss://127.0.0.1:* "
                                        + "http://localhost:* https://localhost:* ws://localhost:* wss://localhost:*; "
                                        + "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'"))
                        .referrerPolicy(referrer -> referrer
                                .policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .permissionsPolicyHeader(policy -> policy
                                .policy("camera=(), microphone=(), geolocation=(), payment=(), usb=()")))
                .sessionManagement(session -> session
                        .maximumSessions(-1)
                        .sessionRegistry(sessionRegistry)
                        .expiredSessionStrategy(expiredSessionStrategy))
                .logout((logout) -> logout
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll());

        http.addFilterBefore(new LoginRateLimitFilter(loginAttemptService),
                UsernamePasswordAuthenticationFilter.class);
        http.addFilterAfter(new JsonRequestSizeLimitFilter(), AuthorizationFilter.class);

        return http.build();
    }

    // 인증 성공 핸들러 (Data Flow Transition)
    // 인증이 성공적으로 완료되면 이 메서드가 실행됩니다.
    // authentication 객체에는 DB에서 조회되어 검증된 사용자 정보(UserDetails)와 권한 목록(Authorities)이
    // 들어있음
    @Bean
    public AuthenticationSuccessHandler myAuthenticationSuccessHandler(LoginAttemptService loginAttemptService) {
        return (request, response, authentication) -> {
            loginAttemptService.recordSuccess(authentication.getName());
            var authorities = authentication.getAuthorities();
            String redirectUrl = "/files"; // 기본 리다이렉트 경로는 파일 브라우저

            // 사용자의 권한을 확인하여 리다이렉트 경로 결정
            for (var authority : authorities) {
                if (authority.getAuthority().equals("ROLE_ADMIN")) {
                    redirectUrl = "/admin/dashboard"; // 관리자는 대시보드로 이동
                    break;
                }
            }
            response.sendRedirect(redirectUrl);
        };
    }

    @Bean
    public org.springframework.security.crypto.password.PasswordEncoder passwordEncoder() {
        return new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
    }

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    public ServletListenerRegistrationBean<HttpSessionEventPublisher> httpSessionEventPublisher() {
        return new ServletListenerRegistrationBean<>(new HttpSessionEventPublisher());
    }

    // @Bean
    // public UserDetailsService userDetailsService(...) {
    // // 인메모리 사용자 관리자를 제거하고 DB 기반의 CustomUserDetailsService를 사용함.
    // // @Service 어노테이션이 붙은 CustomUserDetailsService가 자동으로 감지되어 적용됨.
    // return new InMemoryUserDetailsManager();
    // }
    // 스프링 시큐리티는 빈(Bean)으로 등록된 UserDetailsService를 자동으로 찾아 인증에 사용함.

}
