/*
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Copyright (c) 2026 Vinícius Gabriel Pereira Leitão
 * Licensed under the BSD 3-Clause License.
 * See LICENSE file in the project root for full license information.
 */
package org.migration.sharepoint.infra.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.migration.sharepoint.controller.auth.dto.AuthenticationResponse;
import org.migration.sharepoint.service.auth.AuthService;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final AuthService authService;
    private final Map<String, Object> refreshLocks = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String token = extractToken(request);

        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            AuthenticationResponse.UserResponse profile = authService.validateToken(token);
            setSecurityContext(profile);
        } catch (Exception exception) {
            log.debug("Token validation failed, attempting refresh: {}", exception.getMessage());
            handleTokenRefresh(request, response, token);
        }

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        return java.util.Optional.ofNullable(request.getCookies())
                .map(java.util.Arrays::stream)
                .orElse(java.util.stream.Stream.empty())
                .filter(cookie -> "access_token".equals(cookie.getName()))
                .map(jakarta.servlet.http.Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private void handleTokenRefresh(HttpServletRequest request, HttpServletResponse response, String expiredToken) {
        // Usamos o token expirado como chave para sincronizar requests idênticos
        Object lock = refreshLocks.computeIfAbsent(expiredToken, tokenKey -> new Object());

        synchronized (lock) {
            try {
                // Verifica se um request anterior já resolveu o refresh neste mesmo ciclo
                String alreadyRefreshedToken = response.getHeader("X-New-Access-Token");
                if (alreadyRefreshedToken != null) {
                    AuthenticationResponse.UserResponse profile = authService.validateToken(alreadyRefreshedToken);
                    setSecurityContext(profile);
                } else {
                    AuthenticationResponse newAuthData = authService.refresh(request, response);

                    if (newAuthData != null && newAuthData.session() != null) {
                        AuthenticationResponse.UserSessionResponse newSession = newAuthData.session();
                        AuthenticationResponse.UserResponse profile = authService.validateToken(newSession.accessToken());
                        setSecurityContext(profile);

                        response.setHeader("X-New-Access-Token", newSession.accessToken());
                        response.setHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "X-New-Access-Token");
                    }
                }
            } catch (Exception refreshEx) {
                log.debug("Session refresh failed: {}", refreshEx.getMessage());
                SecurityContextHolder.clearContext();
            } finally {
                refreshLocks.remove(expiredToken);
            }
        }
    }

    private void setSecurityContext(AuthenticationResponse.UserResponse profile) {
        if (profile.roles() != null && profile.roles().contains("ROLE_ADMIN")) {
            List<SimpleGrantedAuthority> authorities =
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN"));
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(profile.profile().username(), null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
    }
}
