package com.erp.ticketing.config;

import com.erp.ticketing.security.CorrelationIdFilter;
import com.erp.ticketing.security.JwtAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Every endpoint lives under {@code /api/helpdesk/**} and is reached through the
 * API gateway, which owns CORS - so CORS is off here, as in the platform's other
 * services. Roles are the helpdesk roles resolved by
 * {@link com.erp.ticketing.security.UserRoleResolver}.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String BASE = "/api/helpdesk";
    private static final String[] STAFF = {"AGENT", "MANAGER", "ADMIN"};
    private static final String[] ALL_ROLES = {"REQUESTER", "AGENT", "MANAGER", "ADMIN"};

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CorrelationIdFilter correlationIdFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter, CorrelationIdFilter correlationIdFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.correlationIdFilter = correlationIdFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(correlationIdFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(jwtAuthenticationFilter, CorrelationIdFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        .requestMatchers(BASE + "/admin/**").hasRole("ADMIN")

                        // Department reads feed assignment dropdowns; changes are admin-only.
                        .requestMatchers(HttpMethod.GET, BASE + "/departments/**").hasAnyRole(ALL_ROLES)
                        .requestMatchers(BASE + "/departments/**").hasRole("ADMIN")


                        // Requesters see their own dashboard, never cross-department aggregates.
                        .requestMatchers(HttpMethod.GET, BASE + "/reports/dashboard/summary").hasAnyRole(ALL_ROLES)
                        .requestMatchers(BASE + "/reports/**").hasAnyRole(STAFF)

                        .requestMatchers(HttpMethod.POST, BASE + "/tickets/bulk-action").hasAnyRole(STAFF)

                        // Problems, changes and major incidents are worked by staff; requesters only get the
                        // customer-facing view of active incidents.
                        .requestMatchers(HttpMethod.GET, BASE + "/major-incidents/public").hasAnyRole(ALL_ROLES)
                        .requestMatchers(BASE + "/problems/**", BASE + "/changes/**", BASE + "/major-incidents/**")
                        .hasAnyRole(STAFF)

                        .requestMatchers(BASE + "/**").hasAnyRole(ALL_ROLES)
                        .anyRequest().denyAll());

        return http.build();
    }

    /**
     * Both filters are {@code @Component}s, which Spring Boot would otherwise also
     * register as plain servlet filters outside the security chain. They run inside
     * the chain above instead, so authorization sees the authentication they set.
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration(CorrelationIdFilter filter) {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
