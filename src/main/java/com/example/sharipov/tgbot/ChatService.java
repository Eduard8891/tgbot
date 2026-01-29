package com.example.sharipov.tgbot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class ChatService {

    private static final Gson GSON = new Gson();

    private final ChatHistoryRepository repo;
    private final RouterClient client;

    private final String model;
    private final String providerOnly;      // может быть пустым
    private final String systemDescription; // system prompt
    private final double temperature;
    private final double topP;

    private final int contextHistoryLimit;  // сколько сообщений уходит в LLM
    private final int dbKeepLimit;          // сколько сообщений оставляем в БД

    public ChatService(ChatHistoryRepository repo,
                       RouterClient client,
                       String model,
                       String providerOnly,
                       String systemDescription,
                       double temperature,
                       double topP,
                       int contextHistoryLimit,
                       int dbKeepLimit) {

        this.repo = repo;
        this.client = client;
        this.model = model;
        this.providerOnly = providerOnly;
        this.systemDescription = systemDescription;
        this.temperature = temperature;
        this.topP = topP;
        this.contextHistoryLimit = contextHistoryLimit;
        this.dbKeepLimit = dbKeepLimit;
    }

    public String reply(long chatId, String userText) {
        String reqId = chatId + "-" + System.nanoTime();
        Instant t0 = Instant.now();

        try {
            List<ChatHistoryRepository.HistoryMessage> history =
                    repo.loadLastMessages(chatId, contextHistoryLimit);

            JsonArray messages = new JsonArray();
            messages.add(msg("system", systemDescription));

            for (var m : history) {
                if (m.content() == null || m.content().isBlank()) continue;
                messages.add(msg(m.role(), m.content()));
            }

            messages.add(msg("user", userText));

            JsonObject req = new JsonObject();
            req.addProperty("model", model);
            req.add("messages", messages);
            req.addProperty("stream", false);

            JsonObject options = new JsonObject();
            options.addProperty("temperature", temperature);
            options.addProperty("top_p", topP);
            req.add("options", options);

            if (providerOnly != null && !providerOnly.isBlank()) {
                JsonObject provider = new JsonObject();
                JsonArray only = new JsonArray();
                only.add(providerOnly);
                provider.add("only", only);
                req.add("provider", provider);
            }

            System.out.printf("%s [CHAT] reqId=%s chatId=%d hist=%d userLen=%d%n",
                    Instant.now(), reqId, chatId, history.size(), userText.length());

            String respBody = client.chat(req, Duration.ofSeconds(120));
            String assistantText = parseAssistant(respBody);

            repo.append(chatId, "user", userText);
            repo.append(chatId, "assistant", assistantText);
            repo.trimToLast(chatId, dbKeepLimit);

            long tookMs = Duration.between(t0, Instant.now()).toMillis();
            System.out.printf("%s [CHAT] reqId=%s done tookMs=%d outLen=%d%n",
                    Instant.now(), reqId, tookMs, assistantText.length());

            return assistantText;

        } catch (Exception e) {
            System.err.printf("%s [CHAT] reqId=%s ERROR: %s%n", Instant.now(), reqId, e.getMessage());
            return "Сервис временно недоступен.";
        }
    }

    private JsonObject msg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    private String parseAssistant(String responseBody) {
        JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
        return json.getAsJsonArray("choices")
                .get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .get("content").getAsString().trim();
    }
}
