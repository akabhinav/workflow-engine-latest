package io.tranto.plugin.core.http;

import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.annotations.PluginProperty;
import io.tranto.core.models.tasks.Output;
import io.tranto.core.models.tasks.RunnableTask;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.runners.RunContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Performs a simple HTTP request using the JDK {@link HttpClient}.
 *
 * <p>The {@code uri} and {@code body} are rendered as dynamic expressions. The
 * {@code method} defaults to {@code GET} and supports {@code GET}, {@code POST},
 * {@code PUT} and {@code DELETE}. Optional {@code headers} are applied verbatim.</p>
 *
 * <p>Non-2xx responses are <em>not</em> treated as errors: the resulting status code
 * and response body are returned so the caller can decide how to react.</p>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Make an HTTP request")
public class Request extends Task implements RunnableTask<Request.HttpOutput> {

    /**
     * Timeout applied when establishing the connection.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Timeout applied to the overall request.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /**
     * The target URI. Supports dynamic expressions.
     */
    @PluginProperty(dynamic = true)
    private String uri;

    /**
     * The HTTP method to use. One of {@code GET}, {@code POST}, {@code PUT} or
     * {@code DELETE}. Defaults to {@code GET}.
     */
    @PluginProperty
    private String method;

    /**
     * The optional request body. Supports dynamic expressions.
     */
    @PluginProperty(dynamic = true)
    private String body;

    /**
     * Optional request headers, applied as-is.
     */
    @PluginProperty
    private Map<String, String> headers;

    @Override
    public HttpOutput run(final RunContext runContext) throws Exception {
        final String renderedUri = runContext.render(uri);
        final String renderedBody = body != null ? runContext.render(body) : null;
        final String httpMethod = method != null ? method.toUpperCase() : "GET";

        final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

        final HttpRequest.BodyPublisher bodyPublisher = renderedBody != null
            ? BodyPublishers.ofString(renderedBody)
            : BodyPublishers.noBody();

        final HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(renderedUri))
            .timeout(REQUEST_TIMEOUT)
            .method(httpMethod, bodyPublisher);

        if (headers != null) {
            for (final Map.Entry<String, String> header : headers.entrySet()) {
                requestBuilder.header(header.getKey(), header.getValue());
            }
        }

        final HttpResponse<String> response =
            client.send(requestBuilder.build(), BodyHandlers.ofString());

        return new HttpOutput(response.statusCode(), response.body());
    }

    /**
     * Output holding the HTTP response.
     *
     * @param status the HTTP status code
     * @param body   the response body
     */
    public record HttpOutput(int status, String body) implements Output {
    }
}
