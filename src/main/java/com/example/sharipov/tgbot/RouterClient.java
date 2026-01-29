package com.example.sharipov.tgbot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

public class RouterClient {

    private static final Gson GSON = new Gson();

    private final HttpClient httpClient;
    private final String llmUrl;
    private final String apiKey;
    private final String referer;
    private final String title;

    public RouterClient(String llmUrl, String apiKey, Duration connectTimeout, String referer, String title) {
        this.llmUrl = llmUrl;
        this.apiKey = apiKey;
        this.referer = referer;
        this.title = title;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
    }

    public String chat(JsonObject requestJson, Duration requestTimeout) throws Exception {
        Instant t0 = Instant.now();

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(llmUrl))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("HTTP-Referer", referer)
                .header("X-Title", title)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(requestJson)))
                .build();

        HttpResponse<String> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());

        long tookMs = Duration.between(t0, Instant.now()).toMillis();
        System.out.printf("%s [LLM_HTTP] status=%d tookMs=%d bodyLen=%d%n",
                Instant.now(), resp.statusCode(), tookMs, resp.body() != null ? resp.body().length() : -1);

        if (resp.statusCode() != 200) {
            throw new RuntimeException("OpenRouter non-200: " + resp.statusCode() + " body=" + resp.body());
        }
        return resp.body();
    }
}
