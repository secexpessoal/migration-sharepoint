/*
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Copyright (c) 2026 Vinícius Gabriel Pereira Leitão
 * Licensed under the BSD 3-Clause License.
 * See LICENSE file in the project root for full license information.
 */
package org.migration.sharepoint.infra.config;

import java.io.IOException;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

@Configuration
public class SpaWebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(@NonNull String resourcePath, @NonNull Resource location)
                            throws IOException {
                        Resource requestedResource = location.createRelative(resourcePath);

                        // NOTE: Se o arquivo exato existir (por exemplo, /assets/index-xyz.js), retorne-o.
                        if (requestedResource.exists() && requestedResource.isReadable()) {
                            return requestedResource;
                        }

                        // NOTE: Se for uma chamada de API que passou por engano, permita que ela retorne um erro 404
                        // naturalmente.
                        if (resourcePath.startsWith("v1/")
                                || resourcePath.startsWith("swagger-ui/")
                                || resourcePath.startsWith("v3/")) {
                            return null;
                        }

                        // NOTE: Caso contrário, trata-se de uma rota SPA com link direto (por exemplo, /login). Retorna
                        // index.html.
                        return new ClassPathResource("/static/index.html");
                    }
                });
    }
}
