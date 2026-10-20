package com.githubci.intelligence.configuration;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class GitHubConfiguration {

    @Bean
    public RestClient githubApiClient(@Value("${github.api-url}") String apiUrl) {
        if (!"https://api.github.com".equals(apiUrl)) {
            throw new IllegalArgumentException("github.api-url must be https://api.github.com");
        }
        return builder().baseUrl(apiUrl)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader("User-Agent", "github-ci-intelligence")
                .build();
    }

    @Bean
    public RestClient githubLogClient() {
        return builder().build();
    }

    private RestClient.Builder builder() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(20));
        return RestClient.builder().requestFactory(factory);
    }
}
