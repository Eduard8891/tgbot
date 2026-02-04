package com.example.sharipov.tgbot;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.Instant;

public class TgBotApplication extends TelegramLongPollingBot {

    private final String botUsername; // без @
    private final ChatService chatService;

    private Long botId; // lazy

    public TgBotApplication(String botToken, String botUsername, ChatService chatService) {
        super(botToken);
        this.botUsername = botUsername;
        this.chatService = chatService;
        log("INIT", "Bot instance created. username=" + botUsername);
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    private long getBotIdSafe() {
        if (botId != null) return botId;
        try {
            botId = execute(new GetMe()).getId();
            log("INIT", "GetMe ok. botId=" + botId);
        } catch (TelegramApiException e) {
            botId = -1L;
            log("INIT", "GetMe failed, botId fallback=" + botId + " err=" + e.getMessage());
        }
        return botId;
    }

    @Override
    public void onUpdateReceived(Update update) {
        long updateId = update.getUpdateId();
        try {
            log("UPD", "id=" + updateId + " hasMessage=" + update.hasMessage());

            if (!update.hasMessage()) return;
            Message msg = update.getMessage();
            if (msg.getText() == null) return;

            long chatId = msg.getChatId();
            Integer msgId = msg.getMessageId();
            String text = msg.getText();

            // 🔥 ========== /help — самая первая команда ==========
            if (text.equals("/help_random_bot") || text.equals("/start_random_bot")) {
                showHelp(chatId);
                return;
            }

            // 🔥 ========== НОВАЯ КОМАНДА: модель + провайдер одной командой ==========
            if (text.startsWith("/set_mp_random_bot ")) {
                String[] parts = text.substring(18).trim().split("\\s+", 2); // максимум 2 части
                if (parts.length < 1 || parts[0].trim().isEmpty()) {
                    sendMessage(chatId, "❌ /setModelProvider модель [провайдер]\nПример: /setModelProvider mistralai/mixtral-8x7b-instruct");
                    return;
                }
                String newModel = parts[0].trim();
                String newProvider = parts.length > 1 ? parts[1].trim() : "";

                chatService.setModel(newModel);
                chatService.setProvider(newProvider.isBlank() ? "" : newProvider);

                String providerStatus = newProvider.isBlank() ? "отключён (любой)" : newProvider;
                sendMessage(chatId, "✅ Модель: `" + newModel + "`\n✅ Провайдер: `" + providerStatus + "`");
                return;
            }

            // 🔥 ========== КОМАНДЫ УПРАВЛЕНИЯ ==========
            if (text.equals("/get_settings_random_bot")) {
                showSettings(chatId);
                return;
            }

            if (text.startsWith("/set_temp_random_bot ")) {
                try {
                    double newTemp = Double.parseDouble(text.substring(9).trim());
                    if (newTemp >= 0 && newTemp <= 2) {
                        chatService.setTemperature(newTemp);
                        sendMessage(chatId, "✅ Температура: " + newTemp);
                    } else {
                        sendMessage(chatId, "❌ Температура 0.0-2.0");
                    }
                } catch (NumberFormatException e) {
                    sendMessage(chatId, "❌ /set_temp_random_bot 0.7");
                }
                return;
            }

            if (text.startsWith("/set_top_p_random_bot ")) {
                try {
                    double newTopP = Double.parseDouble(text.substring(10).trim());
                    if (newTopP >= 0 && newTopP <= 1) {
                        chatService.setTopP(newTopP);
                        sendMessage(chatId, "✅ Top P: " + newTopP);
                    } else {
                        sendMessage(chatId, "❌ Top P 0.0-1.0");
                    }
                } catch (NumberFormatException e) {
                    sendMessage(chatId, "❌ /set_top_p_random_bot 0.8");
                }
                return;
            }

            if (text.startsWith("/set_tokens_random_bot ")) {
                try {
                    int newTokens = Integer.parseInt(text.substring(11).trim());
                    if (newTokens > 0 && newTokens <= 4096) {
                        chatService.setMaxTokens(newTokens);
                        sendMessage(chatId, "✅ Макс. токены: " + newTokens);
                    } else {
                        sendMessage(chatId, "❌ Токены 1-4096");
                    }
                } catch (NumberFormatException e) {
                    sendMessage(chatId, "❌ /set_tokens_random_bot 80");
                }
                return;
            }

            if (text.startsWith("/set_prompt_random_bot ")) {
                String newPrompt = text.substring(11).trim();
                if (!newPrompt.isBlank()) {
                    chatService.setSystemDescription(newPrompt);
                    sendMessage(chatId, "✅ Промпт обновлён (" + newPrompt.length() + " символов)");
                } else {
                    sendMessage(chatId, "❌ /set_prompt_random_bot Новый промпт...");
                }
                return;
            }

            // ========== ОБЫЧНЫЕ СООБЩЕНИЯ ==========
            log("UPD", "chatId=" + chatId + " msgId=" + msgId + " textLen=" + text.length());

            String reply = routeAndReply(msg, text, chatId);
            if (reply == null || reply.isBlank()) {
                log("UPD", "ignored by routing. chatId=" + chatId + " msgId=" + msgId);
                return;
            }

            SendMessage out = SendMessage.builder()
                    .chatId(chatId)
                    .text(reply)
                    .build();
            execute(out);
            log("UPD", "sent. chatId=" + chatId + " msgId=" + msgId);

        } catch (Exception e) {
            System.err.printf("%s [UPD] id=%d ERROR: %s%n", Instant.now(), updateId, e.getMessage());
            if (e instanceof TelegramApiException) ((TelegramApiException) e).printStackTrace();
        }
    }

    private void showSettings(long chatId) {
        StringBuilder info = new StringBuilder("🤖 Настройки:\n\n");

        info.append("📱 Модель: ").append(chatService.getModel()).append("\n");
        if (!chatService.getProviderOnly().isBlank()) {
            info.append("🏭 Провайдер: ").append(chatService.getProviderOnly()).append("\n");
        } else {
            info.append("🏭 Провайдер: любой\n");
        }
        info.append(String.format("🌡️ Температура: %.1f\n", chatService.getTemperature()));
        info.append(String.format("🎲 Top P: %.1f\n", chatService.getTopP()));
        info.append(String.format("📏 Макс. токены: %d\n", chatService.getMaxTokens()));
        info.append("\n📜 System Prompt:\n").append(chatService.getSystemDescription());

        sendMessage(chatId, info.toString());
    }

    private String routeAndReply(Message msg, String text, long chatId) {
        if (msg.getChat().isUserChat()) {
            return chatService.reply(chatId, text);
        }

        String botTag = "@" + botUsername.toLowerCase();
        boolean isTagged = text.toLowerCase().contains(botTag);

        boolean isReplyToBot = msg.getReplyToMessage() != null
                && msg.getReplyToMessage().getFrom() != null
                && msg.getReplyToMessage().getFrom().getId().equals(getBotIdSafe());

        return (isTagged || isReplyToBot) ? chatService.reply(chatId, text) : "";
    }

    private void sendMessage(long chatId, String text) {
        try {
            SendMessage message = SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .build();
            execute(message);
        } catch (TelegramApiException e) {
            log("ERR", "sendMessage failed: " + e.getMessage());
        }
    }

    private void showHelp(long chatId) {
        String helpText = """
        🤖 Бот управления настройками

        📋 Команды управления:
        /help_random_bot или /start_random_bot — это меню
        /get_settings_random_bot — показать текущие настройки

        🔧 Настройки LLM:
        /set_mp_random_bot модель [провайдер] — одной командой!
        /set_temp_random_bot <0-2> — 0.7
        /set_top_p_random_bot <0-1> — 0.8
        /set_tokens_random_bot <1-4096> — 80
        /set_prompt_random_bot <текст> — новый system prompt

        💬 Обычные сообщения:
        • В личке — Бот отвечает всегда
        • В группе — "@username" или reply на сообщение бота

        ❓ Примеры:
        /set_mp_random_bot mistralai/mixtral-8x7b-instruct
        /set_mp_random_bot mistralai/mixtral-8x7b-instruct deepinfra/fp8
        /set_tokens_random_bot 64
        /set_temp_random_bot 1.2
        /set_prompt_random_bot Ты строгий учитель математики
        """;

        sendMessage(chatId, helpText);
    }

    private void log(String tag, String msg) {
        System.out.printf("%s [%s] [%s] %s%n", Instant.now(), tag, Thread.currentThread().getName(), msg);
    }
}
