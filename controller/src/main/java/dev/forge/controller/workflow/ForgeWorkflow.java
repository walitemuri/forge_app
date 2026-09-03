package dev.forge.controller.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;


@Entity
@Table(name = "forge_workflows")
public class ForgeWorkflow {

    @Id
    private String id;

    @Column(
            name = "name",
            nullable = false
    )
    private String name;

    @Column(
            name = "created_at",
            nullable = false
    )
    private Instant createdAt;


    protected ForgeWorkflow() {
    }


    public ForgeWorkflow(
            String id,
            String name) {

        this.id = id;
        this.name = name;
        this.createdAt = Instant.now();
    }


    public String getId() {
        return id;
    }


    public String getName() {
        return name;
    }


    public Instant getCreatedAt() {
        return createdAt;
    }
}
