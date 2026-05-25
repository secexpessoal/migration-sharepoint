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
        RestClient restClient = restClientBuilder.baseUrl(authServerUrl).build();

        String refreshTokenValue = Arrays.stream(
                        Optional.ofNullable(httpRequest.getCookies()).orElse(new Cookie[0]))
                .filter(cookie -> "refresh_token".equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);

        if (refreshTokenValue != null) {
            try {
                // Chama o logout externo e CAPTURA a resposta para repassar os cookies de limpeza
                ResponseEntity<Void> externalLogoutResponse = restClient
                        .post()
                        .uri("/v1/user/logout")
                        .header(HttpHeaders.COOKIE, "refresh_token=" + refreshTokenValue)
                        .retrieve()
                        .toEntity(Void.class);

                // Repassa os cabeçalhos Set-Cookie do servidor de autenticação (a forma mais garantida)
                normalizeAndAddCookies(externalLogoutResponse, httpResponse);
                
                log.debug("Logout externo concluído, cookies de limpeza repassados");
            } catch (Exception exception) {
                log.warn("Falha ao notificar servidor externo sobre logout, procedendo com limpeza local", exception);
            }
        }

        // Fallback local: Caso o servidor externo não envie os cookies de limpeza ou falhe
        // Tentamos limpar os nomes padrão com os atributos mais prováveis
        boolean isSecure = httpRequest.isSecure() || "https".equalsIgnoreCase(httpRequest.getHeader("X-Forwarded-Proto"));
        
        // Limpeza local "segura" para o host atual
        clearLocalCookie(httpResponse, "access_token", isSecure);
        clearLocalCookie(httpResponse, "refresh_token", isSecure);
        
        // Cabeçalho de limpeza profunda (suportado por navegadores modernos)
        httpResponse.setHeader("Clear-Site-Data", "\"cookies\", \"storage\", \"cache\"");
    }

    private void clearLocalCookie(HttpServletResponse response, String name, boolean isSecure) {
        ResponseCookie cookie = ResponseCookie.from(name, "")
                .path("/")
                .maxAge(0)
                .httpOnly(true)
                .secure(isSecure)
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
