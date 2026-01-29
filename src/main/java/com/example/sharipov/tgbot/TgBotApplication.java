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

    private void log(String tag, String msg) {
        System.out.printf("%s [%s] [%s] %s%n", Instant.now(), tag, Thread.currentThread().getName(), msg);
    }
}
