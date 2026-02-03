package com.example.sharipov.tgbot;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;

public class Main {

    public static void main(String[] args) throws Exception {
        CountDownLatch keepAlive = new CountDownLatch(1);

        System.out.println(Instant.now() + " [MAIN] argsCount=" + args.length);
        if (args.length != 12) {  // Увеличено до 12 параметров
            System.err.println("❌ Нужны 12 параметров:");
            System.err.println("BOT_TOKEN LLM_URL API_KEY MODEL PROVIDER BOT_USERNAME DESCRIPTION TEMP TOP_P MAX_TOKENS HISTORY_LIMIT CLEAR_HISTORY");
            System.err.println("MAX_TOKENS: максимум токенов на ответ (например, 64)");
            System.err.println("CLEAR_HISTORY: true/false");
            return;
        }

        String botToken = args[0];
        String llmUrl = args[1];
        String apiKey = args[2];
        String model = args[3];
        String provider = args[4];
        String botUsername = args[5];
        String description = args[6];
        double temperature = parseDouble(args[7], 0.3);
        double topP = parseDouble(args[8], 0.8);
        int maxTokens = parseInt(args[9], 64);  // Новый параметр, дефолт 64 для коротких ответов
        int historyLimit = parseInt(args[10], 8);
        boolean clearHistory = Boolean.parseBoolean(args[11]);

        System.out.println(Instant.now() + " [MAIN] llmUrl=" + llmUrl);
        System.out.println(Instant.now() + " [MAIN] model=" + model + " provider=" + provider);
        System.out.println(Instant.now() + " [MAIN] username=" + botUsername + " temp=" + temperature + " top_p=" + topP + " max_tokens=" + maxTokens);
        System.out.println(Instant.now() + " [MAIN] historyLimit=" + historyLimit + " clearHistory=" + clearHistory);
        System.out.println(Instant.now() + " [MAIN] token=" + mask(botToken) + " apiKey=" + mask(apiKey));

        ChatHistoryRepository repo = new ChatHistoryRepository("bot_history.db");
        repo.init();
        if (clearHistory) repo.clearAll();

        RouterClient client = new RouterClient(
                llmUrl,
                apiKey,
                Duration.ofSeconds(10),
                "https://t.me/" + botUsername,
                "TgBot"
        );

        int dbKeepLimit = Math.max(40, historyLimit * 4);

        ChatService chatService = new ChatService(
                repo,
                client,
                model,
                provider,
                description,
                temperature,
                topP,
                maxTokens,  // Новый параметр
                historyLimit,
                dbKeepLimit
        );

        TgBotApplication bot = new TgBotApplication(botToken, botUsername, chatService);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println(Instant.now() + " [MAIN] Shutdown hook");
            try {
                repo.close();
            } catch (Exception e) {
                System.err.println("Error closing repo: " + e.getMessage());
            }
            keepAlive.countDown();
        }, "shutdown-hook"));

        TelegramBotsApi botsApi = null;
        try {
            botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(bot);
            System.out.println(Instant.now() + " [MAIN] ✅ Bot registered. Polling ACTIVE.");
        } catch (TelegramApiException e) {
            System.err.println(Instant.now() + " [MAIN] ❌ Registration FAILED: " + e.getMessage());
            e.printStackTrace();
            repo.close();
            return;
        } catch (Exception e) {  // ✅ Ловим ВСЕ ошибки
            System.err.println(Instant.now() + " [MAIN] ❌ Unexpected error: " + e.getMessage());
            e.printStackTrace();
            if (repo != null) repo.close();
            return;
        }

        System.out.println(Instant.now() + " [MAIN] 🔄 Polling forever... Ctrl+C to stop");
        keepAlive.await();
    }

    private static double parseDouble(String s, double def) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static String mask(String s) {
        if (s == null) return "null";
        String t = s.trim();
        if (t.length() <= 8) return "****";
        return t.substring(0, 4) + "****" + t.substring(t.length() - 4);
    }
}
