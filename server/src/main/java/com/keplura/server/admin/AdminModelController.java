package com.keplura.server.admin;

import com.keplura.server.admin.AdminModelService.ModelCreateRequest;
import com.keplura.server.admin.AdminModelService.ModelDto;
import com.keplura.server.admin.AdminModelService.ModelPatchRequest;
import com.keplura.server.admin.AdminModelService.ModelToolsRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** ADMIN-KI Scope C/D wire surface (same double-gate as the provider API). */
@RestController
@RequestMapping("/api/v1/admin/models")
public class AdminModelController {

    private final AdminModelService service;

    public AdminModelController(AdminModelService service) {
        this.service = service;
    }

    @GetMapping
    public List<ModelDto> list() {
        return service.list();
    }

    @PostMapping
    public ModelDto create(@RequestBody ModelCreateRequest request) {
        return service.create(request);
    }

    @PatchMapping("/{id}")
    public ModelDto update(@PathVariable Long id, @RequestBody ModelPatchRequest request) {
        return service.update(id, request);
    }

    /** TU-05b: tool use and default tool kinds of a chat model (full replace). */
    @PutMapping("/{id}/tools")
    public ModelDto updateTools(@PathVariable Long id, @RequestBody ModelToolsRequest request) {
        return service.updateTools(id, request);
    }

    /**
     * "Standardmodell" — atomically moves the default slot of the model's
     * type to this row. A EMBEDDING default change NEVER re-indexes (the
     * service logs it; the UI shows the hint from rag-settings).
     */
    @PutMapping("/{id}/default")
    public ModelDto setDefault(@PathVariable Long id) {
        return service.setDefault(id);
    }
}
