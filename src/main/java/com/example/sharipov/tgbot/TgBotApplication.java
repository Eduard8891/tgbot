package com.example.sharipov.tgbot;

import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Универсальный Telegram бот с OpenRouter + SQLite история чатов.
 * 9 ПАРАМЕТРОВ из батника: BOT_TOKEN LLM_URL API_KEY MODEL NAME DESCRIPTION TEMP TOP_P HISTORY_LIMIT
 */
public class TgBotApplication extends TelegramLongPollingBot {

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();

    private final String LLM_URL;
    private final String API_KEY;
    private final String MODEL;
    private final String NAME;
    private final String DESCRIPTION;
    private final double TEMPERATURE;
    private final double TOP_P;
    private final int HISTORY_LIMIT;

    private Connection db;
    private PreparedStatement saveStmt;
    private PreparedStatement loadStmt;
    private PreparedStatement getMaxIndexStmt;
    private PreparedStatement deleteOldStmt;
    private Long botId;
    public TgBotApplication(String botToken, String llmUrl, String apiKey, String model,
                            String name, String description, String temp, String topP, String historyLimit) {

        super(botToken);  // ✅ 6.9+ синтаксис

        this.LLM_URL = requireNonNull(llmUrl, "LLM_URL");
        this.API_KEY = requireNonNull(apiKey, "API_KEY");
        this.MODEL = requireNonNull(model, "MODEL");
        this.NAME = requireNonNull(name, "NAME");
        this.DESCRIPTION = requireNonNull(description, "DESCRIPTION");
        this.TEMPERATURE = parseDouble(temp, "TEMPERATURE", 0.3);
        this.TOP_P = parseDouble(topP, "TOP_P", 0.8);
        this.HISTORY_LIMIT = parseInt(historyLimit, "HISTORY_LIMIT", 8);

        System.out.println("🔥 Инициализация...");
        initDatabase();
        clearAllHistory();

        // ✅ РЕГИСТРАЦИЯ И ЗАПУСК (6.9.7)
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(this);
            System.out.println("✅ BOT REGISTERED & POLLING STARTED!");
        } catch (TelegramApiException e) {
            System.err.println("❌ REGISTRATION FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String requireNonNull(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("❌ " + name + " не передан!");
        }
        return value.trim();
    }

    private double parseDouble(String value, String name, double defaultValue) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private int parseInt(String value, String name, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private void initDatabase() {
        try {
            db = DriverManager.getConnection("jdbc:sqlite:bot_history.db");
            db.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS history (
                    chat_id INTEGER,
                    message_index INTEGER,
                    role TEXT,
                    content TEXT,
                    PRIMARY KEY(chat_id, message_index)
                )
            """);

            saveStmt = db.prepareStatement("INSERT OR REPLACE INTO history (chat_id, message_index, role, content) VALUES (?, ?, ?, ?)");
            loadStmt = db.prepareStatement("SELECT content FROM history WHERE chat_id = ? ORDER BY message_index DESC LIMIT ?");
            getMaxIndexStmt = db.prepareStatement("SELECT COALESCE(MAX(message_index), 0) + 1 AS next_index FROM history WHERE chat_id = ?");
            deleteOldStmt = db.prepareStatement("DELETE FROM history WHERE chat_id = ? AND message_index < ?");

        } catch (SQLException e) {
            System.err.println("❌ БД: " + e.getMessage());
        }
    }

    private Long getBotId() {
        if (botId == null) {
            try {
                botId = execute(new GetMe()).getId();
            } catch (TelegramApiException e) {
                botId = 7145745579L;
                System.err.println("GetMe failed, fallback ID: " + botId);
            }
        }
        return botId;
    }

    private void saveToHistory(long chatId, String role, String content) {
        if (db == null || saveStmt == null) return;

        try {
            getMaxIndexStmt.setLong(1, chatId);
            ResultSet rs = getMaxIndexStmt.executeQuery();
            rs.next();
            int nextIndex = rs.getInt("next_index");
            rs.close();

            saveStmt.setLong(1, chatId);
            saveStmt.setInt(2, nextIndex);
            saveStmt.setString(3, role);
            saveStmt.setString(4, content);
            saveStmt.executeUpdate();

            deleteOldStmt.setLong(1, chatId);
            deleteOldStmt.setInt(2, nextIndex - 20);
            deleteOldStmt.executeUpdate();

        } catch (SQLException e) {
            System.err.println("Ошибка сохранения истории: " + e.getMessage());
        }
    }

    private List<String> loadHistory(long chatId) {
        List<String> history = new ArrayList<>();
        if (db == null || loadStmt == null) return history;

        try {
            loadStmt.setLong(1, chatId);
            loadStmt.setInt(2, HISTORY_LIMIT);  // ✅ ИЗ ПАРАМЕТРА
            try (ResultSet rs = loadStmt.executeQuery()) {
                while (rs.next()) {
                    history.add(rs.getString("content"));
                }
            }
            return history.size() > HISTORY_LIMIT ? history.subList(0, HISTORY_LIMIT) : history;

        } catch (SQLException e) {
            System.err.println("Ошибка загрузки истории: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private JsonObject createMessage(String role, String content) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", role);
        msg.addProperty("content", content);
        return msg;
    }

    private String generateResponse(String userMessage, long chatId) {
        List<String> history = loadHistory(chatId);

        JsonArray messages = new JsonArray();
        messages.add(createMessage("system", DESCRIPTION));  // ✅ SYSTEM ПЕРВЫЙ

        for (int i = 0; i < history.size(); i += 2) {
            if (i < history.size()) {
                messages.add(createMessage("user", history.get(i)));
            }
            if (i + 1 < history.size()) {
                messages.add(createMessage("assistant", history.get(i + 1)));
            }
        }

        messages.add(createMessage("user", userMessage));

        try {
            JsonObject req = new JsonObject();
            req.addProperty("model", MODEL);
            req.add("messages", messages);
            req.addProperty("stream", false);

            JsonObject options = new JsonObject();
            options.addProperty("temperature", TEMPERATURE);  // ✅ ИЗ ПАРАМЕТРА
            options.addProperty("top_p", TOP_P);              // ✅ ИЗ ПАРАМЕТРА
            req.add("options", options);

            // ✅ ЛОГИ ДЛЯ ДЕБАГА
            System.out.println("📤 REQUEST:");
            System.out.println(GSON.toJson(req));
            System.out.println("────────────");

            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(LLM_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + API_KEY)
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(req)))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(httpReq, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
                String reply = json.getAsJsonArray("choices")
                        .get(0).getAsJsonObject()
                        .getAsJsonObject("message")
                        .get("content").getAsString().trim();

                saveToHistory(chatId, "user", userMessage);
                saveToHistory(chatId, "bot", reply);

                return reply;
            } else {
                System.err.println("LLM error: " + response.statusCode() + " → " + response.body());
                return NAME + " сломан (" + response.statusCode() + ")";
            }
        } catch (Exception e) {
            System.err.println("LLM exception: " + e.getMessage());
            return NAME + " недоступен";
        }
    }

    @Override
    public void onUpdateReceived(Update update) {
        System.out.println("💬 UPDATE #" + update.getUpdateId());  // ✅ ЛОГ
        if (!update.hasMessage() || update.getMessage().getText() == null) return;

        long chatId = update.getMessage().getChatId();
        System.out.println("🔍 Chat=" + chatId + " Text='" + update.getMessage().getText() + "'");  // ✅ ЛОГ

        String reply = processMessage(update, chatId);
        if (reply.isEmpty()) return;

        SendMessage msg = SendMessage.builder().chatId(chatId).text(reply).build();
        try {
            execute(msg);
            System.out.println("✅ ОТВЕТ ОТПРАВЛЕН");
        } catch (TelegramApiException e) {
            System.err.println("❌ ОТПРАВКА: " + e.getMessage());
        }
    }

    private String processMessage(Update update, long chatId) {

        System.out.println("🔍 ОБРАБОТКА: " + chatId);

        Message msg = update.getMessage();
        String text = msg.getText();
        if (text == null || text.isEmpty()) return "";

        if (msg.getChat().isUserChat()) return generateResponse(text, chatId);

        String botUsername = "@" + getBotUsername().toLowerCase();
        boolean isTagged = text.toLowerCase().contains(botUsername);
        boolean isReplyToBot = msg.getReplyToMessage() != null &&
                msg.getReplyToMessage().getFrom().getId().equals(getBotId());

        return (isTagged || isReplyToBot) ? generateResponse(text, chatId) : "";
    }

    @Override
    public String getBotUsername() {
        return "its_random_bot";
    }

    private void clearAllHistory() {
        if (db == null) return;
        try {
            PreparedStatement clearAllStmt = db.prepareStatement("DELETE FROM history");
            int deleted = clearAllStmt.executeUpdate();
            clearAllStmt.close();
            System.out.printf("💥 БД очищена: %d записей%n", deleted);
        } catch (SQLException e) {
            System.err.println("Ошибка очистки БД: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        System.out.println("🔥 MAIN Args=" + args.length + ": " + java.util.Arrays.toString(args));
        if (args.length != 9) {
            System.err.println("❌ Нужны 9 параметров!");
            return;
        }
        new TgBotApplication(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8]);
    }
}
