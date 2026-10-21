package com.githubci.intelligence.configuration;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

import static org.junit.jupiter.api.Assertions.*;

class BedrockConfigurationTest {
    @TempDir
    Path directory;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BedrockConfiguration.class, DynamoDbConfiguration.class,
                    S3Configuration.class, SqsConfiguration.class)
            .withPropertyValues("app.bedrock.region=us-west-2",
                    "app.bedrock.model-id=us.amazon.nova-lite-v1:0",
                    "app.dynamodb.region=us-east-1", "app.dynamodb.endpoint=http://dynamodb-local:8000",
                    "app.s3.region=us-east-1", "app.s3.endpoint=http://localstack:4566",
                    "app.s3.path-style-access=true",
                    "app.sqs.region=us-east-1", "app.sqs.endpoint=http://localstack:4566");

    @Test
    void explicitProfileBypassesDummyCredentialsWithoutChangingLocalClients() throws Exception {
        Path credentials = directory.resolve("credentials");
        Files.writeString(credentials, """
                [prushorthaws]
                aws_access_key_id = profile-test-key
                aws_secret_access_key = profile-test-secret
                """);
        runner.withSystemProperties("aws.accessKeyId=local", "aws.secretAccessKey=local",
                        "aws.sharedCredentialsFile=" + credentials,
                        "aws.configFile=" + directory.resolve("no-config"))
                .withPropertyValues("app.bedrock.enabled=true", "app.bedrock.profile=prushorthaws")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var bedrockCredentials = context.getBean("bedrockCredentialsProvider", AwsCredentialsProvider.class);
                    var localCredentials = context.getBean("dynamoDbCredentialsProvider",
                            DefaultCredentialsProvider.class);
                    assertInstanceOf(ProfileCredentialsProvider.class, bedrockCredentials);
                    assertEquals("profile-test-key", bedrockCredentials.resolveCredentials().accessKeyId());
                    assertEquals("local", localCredentials.resolveCredentials().accessKeyId());
                    var bedrock = context.getBean(BedrockRuntimeClient.class).serviceClientConfiguration();
                    assertSame(bedrockCredentials, bedrock.credentialsProvider());
                    assertEquals(Region.US_WEST_2, bedrock.region());
                    var dynamo = context.getBean(DynamoDbClient.class).serviceClientConfiguration();
                    var s3 = context.getBean(S3Client.class).serviceClientConfiguration();
                    var sqs = context.getBean(SqsClient.class).serviceClientConfiguration();
                    assertSame(localCredentials, dynamo.credentialsProvider());
                    assertSame(localCredentials, s3.credentialsProvider());
                    assertSame(localCredentials, sqs.credentialsProvider());
                    assertEquals("http://dynamodb-local:8000", dynamo.endpointOverride().orElseThrow().toString());
                    assertEquals("http://localstack:4566", s3.endpointOverride().orElseThrow().toString());
                    assertEquals("http://localstack:4566", sqs.endpointOverride().orElseThrow().toString());
                    assertEquals(Region.US_EAST_1, dynamo.region());
                    assertEquals(Region.US_EAST_1, s3.region());
                    assertEquals(Region.US_EAST_1, sqs.region());
                });
    }

    @Test
    void absentExplicitProfileKeepsSeparateDefaultChainForWorkloadRoles() {
        runner.withPropertyValues("app.bedrock.enabled=true").run(context -> {
            assertNull(context.getStartupFailure());
            var bedrock = context.getBean("bedrockCredentialsProvider", AwsCredentialsProvider.class);
            assertInstanceOf(DefaultCredentialsProvider.class, bedrock);
            assertNotSame(context.getBean("dynamoDbCredentialsProvider"), bedrock);
        });
    }

    @Test
    void disabledBedrockDoesNotCreateCredentialsProviderOrClient() {
        runner.withPropertyValues("app.bedrock.enabled=false").run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.containsBean("bedrockCredentialsProvider"));
            assertFalse(context.containsBean("bedrockRuntimeClient"));
        });
    }

    @Test
    void enabledBedrockRequiresModelId() {
        runner.withPropertyValues("app.bedrock.enabled=true", "app.bedrock.model-id=")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
}
