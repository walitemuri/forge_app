package dev.forge.controller.task;

import org.springframework.data.jpa.repository.JpaRepository;


public interface ForgeTaskRepository
        extends JpaRepository<ForgeTask, String> {
}