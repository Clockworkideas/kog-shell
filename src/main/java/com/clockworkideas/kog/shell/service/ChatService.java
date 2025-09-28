package com.clockworkideas.kog.shell.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatService {
    private final ChatClient chatClient;
    public String exchange(String message) {
        return chatClient.prompt(message).call().content();
    }
}
