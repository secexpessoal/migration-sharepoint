package org.migration.sharepoint.infra.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.migration.sharepoint.controller.auth.dto.AuthenticationResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Slf4j
public class ForwardAuthFilter extends OncePerRequestFilter {

    @Value("${security.dev-mode.enabled:false}")
    private boolean devModeEnabled;

    @Value("${security.dev-mode.user-email:dev@localhost}")
    private String devUserEmail;

    @Value("${security.dev-mode.user-id:00000000-0000-0000-0000-000000000001}")
    private String devUserId;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        if (SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        String forwardedUser = request.getHeader("X-Forwarded-User");
        String forwardedEmail = request.getHeader("X-Forwarded-Email");
        String forwardedRoles = request.getHeader("X-Forwarded-Roles");

        if (forwardedUser != null || forwardedEmail != null) {
            AuthenticationResponse.UserResponse profile =
                    buildProfileFromHeaders(forwardedUser, forwardedEmail, forwardedRoles);
            setSecurityContext(profile);
            filterChain.doFilter(request, response);
            return;
        }

        if (devModeEnabled) {
            log.debug("Dev mode ativo: usando usuário mock '{}'", devUserEmail);
            AuthenticationResponse.UserResponse devProfile = buildDevProfile();
            setSecurityContext(devProfile);
        }

        filterChain.doFilter(request, response);
    }

    private AuthenticationResponse.UserResponse buildProfileFromHeaders(String userId, String email, String roles) {
        List<String> roleList = roles != null ? List.of(roles.split(",")) : List.of("ROLE_ADMIN");

        AuthenticationResponse.ProfileDto profileData = new AuthenticationResponse.ProfileDto(
                userId != null ? userId : "forwarded-user", "000000", "Forwarded User");

        UUID uuid;
        try {
            uuid = UUID.fromString(userId);
        } catch (Exception e) {
            uuid = UUID.nameUUIDFromBytes((userId != null ? userId : "forwarded-user").getBytes());
        }

        return new AuthenticationResponse.UserResponse(
                uuid, email != null ? email : "forwarded@localhost", true, roleList, profileData);
    }

    private AuthenticationResponse.UserResponse buildDevProfile() {
        AuthenticationResponse.ProfileDto profileData =
                new AuthenticationResponse.ProfileDto("dev.admin", "000001", "Dev Admin");

        return new AuthenticationResponse.UserResponse(
                UUID.fromString(devUserId), devUserEmail, true, List.of("ROLE_ADMIN"), profileData);
    }

    private void setSecurityContext(AuthenticationResponse.UserResponse profile) {
        List<SimpleGrantedAuthority> authorities = profile.roles() != null
                ? profile.roles().stream().map(SimpleGrantedAuthority::new).toList()
                : Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN"));

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(profile, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
