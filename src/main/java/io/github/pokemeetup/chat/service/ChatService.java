package io.github.pokemeetup.chat.service;

import io.github.pokemeetup.chat.model.ChatMessage;
import io.github.pokemeetup.chat.event.ChatListener;

import java.util.List;
import java.util.Queue;

public interface ChatService {
    void sendMessage(String content);
    void addSystemMessage(String message);
    void handleIncomingMessage(ChatMessage message);
    void activateChat();
    void deactivateChat();
    boolean isActive();
    List<ChatMessage> pollMessages();
    Queue<ChatMessage> getMessages();

    String getPreviousHistoryMessage(String currentText);
    String getNextHistoryMessage();

    // **Add the following methods**
    void addListener(ChatListener listener);
    void removeListener(ChatListener listener);
}
