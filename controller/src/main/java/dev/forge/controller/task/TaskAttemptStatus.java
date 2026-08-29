package dev.forge.controller.task;


public enum TaskAttemptStatus {

    CREATED,

    DISPATCHED,

    RUNNING,

    SUCCEEDED,

    FAILED,

    LOST,

    CANCELLED
}