package com.gamepub.server.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.gamepub.server.common.BusinessException;
import com.gamepub.server.common.ErrorCode;
import com.gamepub.server.config.TikAgentProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class ModelCatalog {
    private final Map<String, AgentClient> clients;
    private final List<ModelDescriptor> descriptors;

    public ModelCatalog(TikAgentProperties properties,
                        @Qualifier("agentClients") Map<String, AgentClient> configuredClients) {
        this.clients = new LinkedHashMap<>(configuredClients);
        List<ModelDescriptor> models = new ArrayList<>();
        properties.agent().models().forEach((id, model) -> {
            if (model.enabled() && this.clients.containsKey(id)) {
                models.add(new ModelDescriptor(id, model.displayName(), model.provider(),
                        id.equals(properties.agent().defaultModel())));
            }
        });
        if (!this.clients.containsKey("mock")) {
            this.clients.put("mock", new MockAgentClient());
            models.add(new ModelDescriptor("mock", "本地 Mock", "mock",
                    "mock".equals(properties.agent().defaultModel())));
        }
        models.sort(Comparator.comparing(ModelDescriptor::id));
        this.descriptors = List.copyOf(models);
    }

    public List<ModelDescriptor> availableModels() { return descriptors; }

    public AgentClient requireClient(String modelId) {
        AgentClient client = clients.get(modelId);
        if (client == null) {
            throw new BusinessException(ErrorCode.MODEL_NOT_AVAILABLE, "模型不可用: " + modelId);
        }
        return client;
    }
}
