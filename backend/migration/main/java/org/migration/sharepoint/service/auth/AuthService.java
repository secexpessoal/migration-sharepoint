/*
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Copyright (c) 2026 Vinícius Gabriel Pereira Leitão
 * Licensed under the BSD 3-Clause License.
 * See LICENSE file in the project root for full license information.
 */
package org.migration.sharepoint.service.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.migration.sharepoint.controller.auth.dto.AuthenticationResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${auth.server.url}")
    private String authServerUrl;

    private void normalizeAndAddCookies(ResponseEntity<?> externalResponse, HttpServletResponse authResponse) {
        Optional.ofNullable(externalResponse.getHeaders().get(HttpHeaders.SET_COOKIE))
                .ifPresent(setCookies -> setCookies.stream()
                        .map(this::normalizeCookie)
                        .forEach(normalizedCookie -> authResponse.addHeader(HttpHeaders.SET_COOKIE, normalizedCookie)));
    }

    private String normalizeCookie(String cookieHeader) {
        // Força Path=/ e mantém HttpOnly/Secure se existirem no original
        String normalized = cookieHeader.replaceAll("(?i)Path=[^;]+", "Path=/");
        if (!normalized.toLowerCase().contains("path=/")) {
            normalized += "; Path=/";
        }
        // Garante que o refresh_token especificamente seja HttpOnly se o servidor de auth esqueceu
        if (normalized.startsWith("refresh_token=") && !normalized.toLowerCase().contains("httponly")) {
            normalized += "; HttpOnly";
        }
        return normalized;
    }

    public AuthenticationResponse.UserResponse validateToken(String accessToken) {
        RestClient restClient = restClientBuilder.baseUrl(authServerUrl).build();

        return restClient
                .get()
                .uri("/v1/user/profile")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (clientRequest, clientResponse) -> {
                    throw new BadCredentialsException("Sessão inválida");
                })
                .body(AuthenticationResponse.UserResponse.class);
    }

    public AuthenticationResponse refresh(
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        RestClient restClient = restClientBuilder.baseUrl(authServerUrl).build();

        String refreshTokenCookie = Arrays.stream(
                        Optional.ofNullable(httpRequest.getCookies()).orElse(new Cookie[0]))
                .filter(cookie -> "refresh_token".equals(cookie.getName()))
                .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                .findFirst()
                .orElse(null);

        if (refreshTokenCookie == null) {
            throw new BadCredentialsException("Nenhum token de atualização encontrado");
        }

        ResponseEntity<AuthenticationResponse> refreshResponse = restClient
                .post()
                .uri("/v1/user/refresh")
                .header(HttpHeaders.COOKIE, refreshTokenCookie)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (clientRequest, clientResponse) -> {
                    throw new BadCredentialsException("Não foi possível atualizar a sessão");
                })
                .toEntity(AuthenticationResponse.class);

        normalizeAndAddCookies(refreshResponse, httpResponse);

        return refreshResponse.getBody();
    }

    public void logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        log.info("Iniciando processo de logout no AuthService");
        RestClient restClient = restClientBuilder.baseUrl(authServerUrl).build();

        String refreshTokenValue = Arrays.stream(
                        Optional.ofNullable(httpRequest.getCookies()).orElse(new Cookie[0]))
                .filter(cookie -> "refresh_token".equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);

        if (refreshTokenValue != null) {
            try {
                log.info("Notificando servidor de autenticação externo...");
                ResponseEntity<Void> externalLogoutResponse = restClient
                        .post()
                        .uri("/v1/user/logout")
                        .header(HttpHeaders.COOKIE, "refresh_token=" + refreshTokenValue)
                        .retrieve()
                        .toEntity(Void.class);

                normalizeAndAddCookies(externalLogoutResponse, httpResponse);
                log.info("Servidor externo notificado com sucesso");
            } catch (Exception exception) {
                log.warn("Falha ao notificar servidor externo sobre logout: {}", exception.getMessage());
            }
        }

        // Determinação de segurança baseada nos headers do proxy e estado da conexão
        boolean isSecure = httpRequest.isSecure() 
                || "https".equalsIgnoreCase(httpRequest.getHeader("X-Forwarded-Proto"))
                || "https".equalsIgnoreCase(httpRequest.getHeader("X-Forwarded-Scheme"));
        
        String host = httpRequest.getServerName();
        log.info("Host detectado para limpeza de cookies: {}, IsSecure (detectado): {}", host, isSecure);

        // Cookies exatos reportados pelo usuário
        List<String> cookiesToClear = List.of("access_token", "refresh_token", "__session", "XSRF-TOKEN");

        cookiesToClear.forEach(name -> {
            // 1. Limpa no Host atual (sem domínio específico)
            addClearCookieHeader(httpResponse, name, null, isSecure);
            
            // 2. Limpa no Domínio pai com ponto (ex: .secexpessoal.org)
            if (host != null && host.contains(".")) {
                String domain = host.substring(host.indexOf("."));
                if (domain.length() > 1) {
                    addClearCookieHeader(httpResponse, name, domain, isSecure);
                }
            }
        });
        
        // Instrução final para o navegador limpar tudo
        httpResponse.setHeader("Clear-Site-Data", "\"cookies\", \"storage\", \"cache\"");
        log.info("Headers de limpeza de cookies adicionados à resposta");
    }

    private void addClearCookieHeader(HttpServletResponse response, String name, String domain, boolean isSecure) {
        // Forçamos Secure se for detectado ou se estivermos em produção (secexpessoal.org costuma ser HTTPS)
        boolean forceSecure = isSecure || (domain != null && domain.contains("secexpessoal.org"));

        ResponseCookie cookie = ResponseCookie.from(name, "")
                .path("/")
                .maxAge(0)
                .secure(forceSecure) 
                .httpOnly(true)
                .sameSite("Lax")
                .domain(domain)
                .build();
        
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        log.info("Enviado Set-Cookie para limpeza: name={}, domain={}, secure={}", name, domain, forceSecure);
    }
}
