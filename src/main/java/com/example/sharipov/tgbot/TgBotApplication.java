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
            if (text.equals("/help") || text.equals("/start")) {
                showHelp(chatId);
                return;
            }

            // 🔥 ========== КОМАНДЫ УПРАВЛЕНИЯ ==========
            if (text.equals("/getSettings")) {
                showSettings(chatId);
                return;
            }


            if (text.startsWith("/setModel ")) {
                String newModel = text.substring(10).trim();
                if (!newModel.isBlank()) {
                    chatService.setModel(newModel);
                    sendMessage(chatId, "✅ Модель: `" + newModel + "`");
                } else {
                    sendMessage(chatId, "❌ /setModel mistralai/mixtral-8x7b-instruct");
                }
                return;
            }

            if (text.startsWith("/setProvider ")) {
                String newProvider = text.substring(13).trim();
                chatService.setProvider(newProvider.isBlank() ? "" : newProvider);
                String status = newProvider.isBlank() ? "отключён (любой)" : newProvider;
                sendMessage(chatId, "✅ Провайдер: " + status);
                return;
            }

            if (text.startsWith("/setTemp ")) {
                try {
                    double newTemp = Double.parseDouble(text.substring(9).trim());
                    if (newTemp >= 0 && newTemp <= 2) {
                        chatService.setTemperature(newTemp);
                        sendMessage(chatId, "✅ Температура: " + newTemp);
                    } else {
                        sendMessage(chatId, "❌ Температура 0.0-2.0");
                    }
                } catch (NumberFormatException e) {
                    sendMessage(chatId, "❌ /setTemp 0.7");
                }
                return;
            }

            if (text.startsWith("/setTopP ")) {
                try {
                    double newTopP = Double.parseDouble(text.substring(10).trim());
                    if (newTopP >= 0 && newTopP <= 1) {
                        chatService.setTopP(newTopP);
                        sendMessage(chatId, "✅ Top P: " + newTopP);
                    } else {
                        sendMessage(chatId, "❌ Top P 0.0-1.0");
                    }
                } catch (NumberFormatException e) {
                    sendMessage(chatId, "❌ /setTopP 0.8");
                }
                return;
            }

            if (text.startsWith("/setTokens ")) {
                try {
                    int newTokens = Integer.parseInt(text.substring(11).trim());
                    if (newTokens > 0 && newTokens <= 4096) {
                        chatService.setMaxTokens(newTokens);
                        sendMessage(chatId, "✅ Макс. токены: " + newTokens);
                    } else {
                        sendMessage(chatId, "❌ Токены 1-4096");
                    }
                } catch (NumberFormatException e) {
                    sendMessage(chatId, "❌ /setTokens 80");
                }
                return;
            }

            if (text.startsWith("/setPrompt ")) {
                String newPrompt = text.substring(11).trim();
                if (!newPrompt.isBlank()) {
                    chatService.setSystemDescription(newPrompt);
                    sendMessage(chatId, "✅ Промпт обновлён (" + newPrompt.length() + " символов)");
                } else {
                    sendMessage(chatId, "❌ /setPrompt Новый промпт...");
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
        StringBuilder info = new StringBuilder("🤖 **Настройки Палыча:**\n\n");

        info.append("📱 **Модель**: ").append(chatService.getModel()).append("\n");
        if (!chatService.getProviderOnly().isBlank()) {
            info.append("🏭 **Провайдер**: ").append(chatService.getProviderOnly()).append("\n");
        } else {
            info.append("🏭 **Провайдер**: любой\n");
        }
        info.append(String.format("🌡️ **Температура**: %.1f\n", chatService.getTemperature()));
        info.append(String.format("🎲 **Top P**: %.1f\n", chatService.getTopP()));
        info.append(String.format("📏 **Макс. токены**: %d\n", chatService.getMaxTokens()));
        info.append("\n📜 **System Prompt**:\n").append(chatService.getSystemDescription());

        sendMessage(chatId, info.toString());
    }

    private String routeAndReply(Message msg, String text, long chatId) {
        // Личка: отвечаем всегда
        if (msg.getChat().isUserChat()) {
            return chatService.reply(chatId, text);
        }

        // Группа: отвечаем только если упомянули @username или ответили на сообщение бота
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
        🤖 **Бот управления настройками**

        📋 **Команды управления:**
        `/help` или `/start` — это меню
        `/getSettings` — показать текущие настройки

        🔧 **Настройки LLM (меняются на лету):**
        `/setModel <модель>` — `mistralai/mixtral-8x7b-instruct`
        `/setProvider <провайдер>` — `deepinfra/fp8` или пусто
        `/setTemp <0-2>` — `0.7`
        `/setTopP <0-1>` — `0.8`  
        `/setTokens <1-4096>` — `80`
        `/setPrompt <текст>` — новый system prompt

        💬 **Обычные сообщения:**
        • В **личке** — Бот отвечает всегда
        • В **группе** — `@username` или **reply** на сообщение бота

        ❓ Примеры:
        `/setTokens 64`
        `/setTemp 1.2` 
        `/setPrompt Ты строгий учитель математики`
        """;

        sendMessage(chatId, helpText);
    }


    private void log(String tag, String msg) {
        System.out.printf("%s [%s] [%s] %s%n", Instant.now(), tag, Thread.currentThread().getName(), msg);
    }
}
