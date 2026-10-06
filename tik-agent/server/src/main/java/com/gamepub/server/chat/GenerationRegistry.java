package com.gamepub.server.chat;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.gamepub.server.common.BusinessException;
import com.gamepub.server.common.ErrorCode;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

@Component
public class GenerationRegistry {
    private final ConcurrentMap<Long, GenerationHandle> active = new ConcurrentHashMap<>();

    public GenerationHandle acquire(long conversationId) {
        GenerationHandle handle = new GenerationHandle(conversationId);
        if (active.putIfAbsent(conversationId, handle) != null) {
            throw new BusinessException(ErrorCode.GENERATION_IN_PROGRESS, "该会话已有回答正在生成");
        }
        return handle;
    }

    public void release(long conversationId, GenerationHandle handle) {
        active.remove(conversationId, handle);
    }

    public int activeCount() {
        return active.size();
    }

    public final class GenerationHandle {
        private final long conversationId;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<Disposable> subscription = new AtomicReference<>();

        private GenerationHandle(long conversationId) {
            this.conversationId = conversationId;
        }

        public long conversationId() {
            return conversationId;
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        public void bind(Disposable disposable) {
            if (!subscription.compareAndSet(null, disposable) || cancelled.get()) {
                disposable.dispose();
            }
        }

        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                Disposable disposable = subscription.get();
                if (disposable != null) {
                    disposable.dispose();
                }
            }
        }
    }
}
