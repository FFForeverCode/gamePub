package com.gamepub.server.agent;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/models")
public class ModelController {
    private final ModelCatalog modelCatalog;

    public ModelController(ModelCatalog modelCatalog) { this.modelCatalog = modelCatalog; }

    @GetMapping
    public List<ModelDescriptor> models() { return modelCatalog.availableModels(); }
}
