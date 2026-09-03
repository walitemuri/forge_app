package dev.forge.controller.workflow;

import org.springframework.data.jpa.repository.JpaRepository;


public interface ForgeWorkflowRepository
        extends JpaRepository<ForgeWorkflow, String> {
}
