package com.funding.trigger.lambda;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** {@code java.net.http} adapter. The API key goes in {@code X-Trigger-Api-Key} (AS-1.7). */
public final class HttpStartApi implements StartApi {

    public static final String PATH = "/api/v1/funding-workflows";

    private final HttpClient client;
    private final URI uri;
    private final String apiKey;
    private final Duration timeout;

    public HttpStartApi(String baseUrl, String apiKey, Duration timeout) {
        this.uri = URI.create(baseUrl.replaceAll("/+$", "") + PATH);
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public Response post(String jsonBody) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("X-Trigger-Api-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        return new Response(resp.statusCode(), resp.body());
    }
}
