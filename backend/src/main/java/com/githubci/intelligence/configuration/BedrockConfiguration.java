package com.githubci.intelligence.configuration;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

@Configuration
public class BedrockConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "app.bedrock.enabled", havingValue = "true")
    public AwsCredentialsProvider bedrockCredentialsProvider(
            @Value("${app.bedrock.profile:}") String profile) {
        return profile.isBlank() ? DefaultCredentialsProvider.builder().build()
                : ProfileCredentialsProvider.builder().profileName(profile).build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "app.bedrock.enabled", havingValue = "true")
    public BedrockRuntimeClient bedrockRuntimeClient(
            @Qualifier("bedrockCredentialsProvider") AwsCredentialsProvider credentials,
            @Value("${app.bedrock.region}") String region,
            @Value("${app.bedrock.model-id}") String modelId) {
        if (modelId.isBlank()) {
            throw new IllegalArgumentException("BEDROCK_MODEL_ID is required when Bedrock is enabled");
        }
        return BedrockRuntimeClient.builder().credentialsProvider(credentials).region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(20)))
                .overrideConfiguration(config -> config.apiCallAttemptTimeout(Duration.ofSeconds(25))
                        .apiCallTimeout(Duration.ofSeconds(30))
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()))
                .build();
    }
}
