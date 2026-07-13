package io.tranto.plugin.aws;

import io.tranto.core.exceptions.IllegalVariableEvaluationException;
import io.tranto.core.models.annotations.PluginProperty;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.runners.RunContext;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.regions.Region;

import java.net.URI;

/**
 * Shared configuration for every AWS task: region, credentials, and an optional endpoint override.
 * When {@code accessKeyId}/{@code secretKeyId} are omitted, the AWS SDK's default credentials provider
 * chain is used (env vars, profile, instance/container role) — the production-standard behaviour.
 * {@code endpointOverride} points the client at a local stack (e.g. LocalStack) for testing.
 *
 * <p>Subclasses obtain their service client by calling {@link #configure} on the SDK's client builder;
 * they expose an overridable {@code client(RunContext)} seam so unit tests can inject a mock client
 * without touching AWS.</p>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
public abstract class AbstractAwsTask extends Task {

    /** AWS access key id (dynamic). Omit to use the default credentials provider chain. */
    @PluginProperty(dynamic = true)
    protected String accessKeyId;

    /** AWS secret access key (dynamic). Omit to use the default credentials provider chain. */
    @PluginProperty(dynamic = true)
    protected String secretKeyId;

    /** Optional STS session token (dynamic) for temporary credentials. */
    @PluginProperty(dynamic = true)
    protected String sessionToken;

    /** AWS region, e.g. {@code us-east-1} (dynamic). */
    @PluginProperty(dynamic = true)
    protected String region;

    /** Optional endpoint override (dynamic), e.g. a LocalStack URL {@code http://localhost:4566}. */
    @PluginProperty(dynamic = true)
    protected String endpointOverride;

    /** Apply region, credentials and endpoint override to an AWS SDK client builder. */
    protected <B extends AwsClientBuilder<B, ?>> B configure(final B builder, final RunContext runContext)
        throws IllegalVariableEvaluationException {
        if (region != null && !region.isBlank()) {
            builder.region(Region.of(runContext.render(region)));
        }
        AwsCredentials credentials = credentials(runContext);
        if (credentials != null) {
            builder.credentialsProvider(StaticCredentialsProvider.create(credentials));
        }
        if (endpointOverride != null && !endpointOverride.isBlank()) {
            builder.endpointOverride(URI.create(runContext.render(endpointOverride)));
        }
        return builder;
    }

    /** @return static credentials from the task properties, or null to fall back to the default chain. */
    private AwsCredentials credentials(final RunContext runContext) throws IllegalVariableEvaluationException {
        if (accessKeyId == null || secretKeyId == null) {
            return null;
        }
        String key = runContext.render(accessKeyId);
        String secret = runContext.render(secretKeyId);
        if (sessionToken != null && !sessionToken.isBlank()) {
            return AwsSessionCredentials.create(key, secret, runContext.render(sessionToken));
        }
        return AwsBasicCredentials.create(key, secret);
    }
}
