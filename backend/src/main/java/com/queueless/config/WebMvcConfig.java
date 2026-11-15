package com.queueless.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.File;
import java.io.IOException;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Serve from classpath static or local frontend/dist
        File frontendDist = new File("frontend/dist");
        if (!frontendDist.exists()) {
            frontendDist = new File("../frontend/dist");
        }

        String location = frontendDist.exists() ?
                "file:" + frontendDist.getAbsolutePath() + "/" : "classpath:/static/";

        registry.addResourceHandler("/**")
                .addResourceLocations(location, "classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        // Don't forward /api or /h2-console
                        if (resourcePath.startsWith("api") || resourcePath.startsWith("h2-console")) {
                            return null;
                        }

                        Resource requestedResource = location.createRelative(resourcePath);
                        if (requestedResource.exists() && requestedResource.isReadable()) {
                            return requestedResource;
                        }

                        // Forward client-side React routes to index.html
                        Resource indexResource = location.createRelative("index.html");
                        if (indexResource.exists() && indexResource.isReadable()) {
                            return indexResource;
                        }

                        return new ClassPathResource("/static/index.html");
                    }
                });
    }
}
