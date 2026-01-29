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
        if (args.length != 11) {
            System.err.println("❌ Нужны 11 параметров:");
            System.err.println("BOT_TOKEN LLM_URL API_KEY MODEL PROVIDER BOT_USERNAME DESCRIPTION TEMP TOP_P HISTORY_LIMIT CLEAR_HISTORY");
            System.err.println("CLEAR_HISTORY: true/false");
            return;
        }

        String botToken = args[0];
        String llmUrl = args[1];
        String apiKey = args[2];
        String model = args[3];
        String provider = args[4];
        String botUsername = args[5];          // без @
        String description = args[6];
        double temperature = parseDouble(args[7], 0.3);
        double topP = parseDouble(args[8], 0.8);
        int historyLimit = parseInt(args[9], 8);
        boolean clearHistory = Boolean.parseBoolean(args[10]);

        System.out.println(Instant.now() + " [MAIN] llmUrl=" + llmUrl);
        System.out.println(Instant.now() + " [MAIN] model=" + model + " provider=" + provider);
        System.out.println(Instant.now() + " [MAIN] username=" + botUsername + " temp=" + temperature + " top_p=" + topP);
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

        // dbKeepLimit: сколько сообщений физически оставляем в БД на чат
        int dbKeepLimit = Math.max(40, historyLimit * 4);

        ChatService chatService = new ChatService(
                repo,
                client,
                model,
                provider,
                description,
                temperature,
                topP,
                historyLimit,
                dbKeepLimit
        );

        TgBotApplication bot = new TgBotApplication(botToken, botUsername, chatService);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println(Instant.now() + " [MAIN] Shutdown hook");
            repo.close();
            keepAlive.countDown();
        }, "shutdown-hook"));

        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(bot);
            System.out.println(Instant.now() + " [MAIN] Registered. Polling started.");
        } catch (TelegramApiException e) {
            System.err.println(Instant.now() + " [MAIN] Registration failed: " + e.getMessage());
            e.printStackTrace();
            repo.close();
            return;
        }

        System.out.println(Instant.now() + " [MAIN] Running... Ctrl+C to stop");
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
