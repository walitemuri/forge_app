package dev.forge.controller.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;


public interface ForgeWorkflowRepository
        extends JpaRepository<ForgeWorkflow, String> {

    List<ForgeWorkflow>
    findAllByOrderByCreatedAtDesc();
}