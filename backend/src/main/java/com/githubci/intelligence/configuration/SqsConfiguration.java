package com.githubci.intelligence.configuration;

import java.net.URI;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
@EnableScheduling
public class SqsConfiguration {

    @Bean(destroyMethod = "close")
    public SqsClient sqsClient(
            @Value("${app.sqs.endpoint}") String endpoint,
            @Value("${app.sqs.region}") String region,
            @Qualifier("dynamoDbCredentialsProvider") DefaultCredentialsProvider credentials) {
        var builder = SqsClient.builder()
                .region(Region.of(region)).credentialsProvider(credentials)
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(25)))
                .overrideConfiguration(configuration -> configuration
                        .apiCallAttemptTimeout(Duration.ofSeconds(30)).apiCallTimeout(Duration.ofSeconds(60)));
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }
}
