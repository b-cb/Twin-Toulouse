package com.tisseo.twin.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;

@Configuration
public class TisseoClientConfig {

    @Value("${tisseo.api.base-url}")
    private String tisseoApiString;

    @Value("${tisseo.api.key}")
    private String key;

    @Bean
    public RestClient tisseoRestClient(RestClient.Builder builder) {
        return builder
                .baseUrl(tisseoApiString)
                .requestInterceptor(this::addApiKeyInterceptor)
                .build();
    }

    private ClientHttpResponse addApiKeyInterceptor(HttpRequest request, byte[] body,
            ClientHttpRequestExecution execution) throws IOException {

        URI originalUri = request.getURI();

        URI newUri = UriComponentsBuilder.fromUri(originalUri)
                .queryParam("key", key)
                .build(true)
                .toUri();

        HttpRequestWrapper newRequest = new HttpRequestWrapper(request) {
            @Override
            public URI getURI() {
                return newUri;
            }
        };

        return execution.execute(newRequest, body);
    }
}