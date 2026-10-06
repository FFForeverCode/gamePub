package com.gamepub.server.chat;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.gamepub.server.common.ErrorCode;
import com.gamepub.server.conversation.Message;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@Service
public class ChatStreamService {
    private final ChatService chatService;
    private final GenerationRegistry generationRegistry;
    private final Duration streamTimeout;

    public ChatStreamService(ChatService chatService, GenerationRegistry generationRegistry,
                             com.gamepub.server.config.TikAgentProperties properties) {
        this.chatService = chatService;
        this.generationRegistry = generationRegistry;
        this.streamTimeout = properties.chat().streamTimeout();
    }

    public SseEmitter open(long conversationId, ChatCommand command) {
        GenerationRegistry.GenerationHandle handle = generationRegistry.acquire(conversationId);
        ChatPreparation preparation;
        try {
            preparation = chatService.prepare(conversationId, command);
        } catch (RuntimeException exception) {
            generationRegistry.release(conversationId, handle);
            throw exception;
        }

        SseEmitter emitter = new SseEmitter(streamTimeout.toMillis());
        AtomicBoolean terminal = new AtomicBoolean();
        AtomicLong eventId = new AtomicLong();
        StringBuilder answer = new StringBuilder();
        Message assistant = preparation.pair().assistantMessage();

        Runnable cancel = () -> {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            try {
                handle.cancel();
                String content = answer.toString();
                chatService.cancel(assistant, content);
                Message cancelled = terminalMessage(assistant, content,
                        com.gamepub.server.conversation.MessageStatus.CANCELLED, "CANCELLED");
                send(emitter, eventId.incrementAndGet(), "cancelled", SseEventFactory.cancelled(cancelled));
            } finally {
                generationRegistry.release(conversationId, handle);
                emitter.complete();
            }
        };

        emitter.onTimeout(cancel);
        emitter.onCompletion(cancel);
        emitter.onError(error -> cancel.run());

        send(emitter, eventId.incrementAndGet(), "message",
                SseEventFactory.messageCreated(preparation.pair().userMessage(), assistant));

        Flux<String> stream;
        try {
            stream = preparation.client().stream(preparation.context())
                    .timeout(streamTimeout)
                    .subscribeOn(Schedulers.boundedElastic());
        } catch (RuntimeException exception) {
            fail(emitter, terminal, eventId, conversationId, handle, assistant, answer, exception);
            return emitter;
        }

        Disposable disposable = stream.subscribe(
                delta -> {
                    if (terminal.get() || handle.isCancelled()) {
                        return;
                    }
                    synchronized (answer) {
                        answer.append(delta);
                    }
                    send(emitter, eventId.incrementAndGet(), "delta",
                            new SseEventFactory.Delta(assistant.getId(), delta));
                },
                exception -> fail(emitter, terminal, eventId, conversationId, handle, assistant, answer, exception),
                () -> {
                    if (!terminal.compareAndSet(false, true)) {
                        return;
                    }
                    String content;
                    synchronized (answer) {
                        content = answer.toString();
                    }
                    chatService.complete(conversationId, assistant, content);
                    Message completed = terminalMessage(assistant, content,
                            com.gamepub.server.conversation.MessageStatus.COMPLETED, null);
                    send(emitter, eventId.incrementAndGet(), "complete", SseEventFactory.complete(completed));
                    generationRegistry.release(conversationId, handle);
                    emitter.complete();
                });
        handle.bind(disposable);
        return emitter;
    }

    private void fail(SseEmitter emitter, AtomicBoolean terminal, AtomicLong eventId, long conversationId,
                      GenerationRegistry.GenerationHandle handle, Message assistant, StringBuilder answer,
                      Throwable exception) {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        String content;
        synchronized (answer) {
            content = answer.toString();
        }
        String code = exception instanceof java.util.concurrent.TimeoutException
                ? ErrorCode.MODEL_TIMEOUT.name() : ErrorCode.MODEL_UNAVAILABLE.name();
        chatService.fail(assistant, content, code);
        send(emitter, eventId.incrementAndGet(), "error",
                new SseEventFactory.StreamError(assistant.getId(), code, "模型生成失败"));
        generationRegistry.release(conversationId, handle);
        emitter.complete();
    }

    private boolean send(SseEmitter emitter, long eventId, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().id(Long.toString(eventId)).name(event)
                    .data(data, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private Message terminalMessage(Message source, String content,
                                    com.gamepub.server.conversation.MessageStatus status, String errorCode) {
        return new Message(source.getId(), source.getConversationId(), source.getRole(), content, status,
                source.getModelId(), source.getSequenceNo(), errorCode, source.getCreatedAt(),
                java.time.LocalDateTime.now());
    }
}
