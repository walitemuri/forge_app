package dev.forge.controller.api;

import dev.forge.controller.workflow.WorkflowService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final WorkflowService
            workflowService;


    public WorkflowController(
            WorkflowService workflowService) {

        this.workflowService =
                workflowService;
    }


    @PostMapping
    public ResponseEntity<?> createWorkflow(
            @RequestBody CreateWorkflowRequest request) {

        try {

            WorkflowResponse workflow =
                    workflowService
                            .createWorkflow(
                                    request
                            );


            return ResponseEntity
                    .status(
                            HttpStatus.CREATED
                    )
                    .body(
                            workflow
                    );
        }

        catch (IllegalArgumentException exception) {

            return ResponseEntity
                    .badRequest()
                    .body(
                            exception.getMessage()
                    );
        }
    }


    @GetMapping("/{workflowId}")
    public ResponseEntity<WorkflowResponse>
            getWorkflow(
                    @PathVariable String workflowId) {

        WorkflowResponse workflow =
                workflowService
                        .getWorkflow(
                                workflowId
                        );


        if (workflow == null) {

            return ResponseEntity
                    .notFound()
                    .build();
        }


        return ResponseEntity.ok(
                workflow
        );
    }

    @PostMapping("/{workflowId}/cancel")
    public ResponseEntity<?> cancelWorkflow(
            @PathVariable String workflowId) {
    
        try {
    
            WorkflowResponse workflow =
                    workflowService
                            .cancelWorkflow(
                                    workflowId
                            );
    
    
            if (workflow == null) {
    
                return ResponseEntity
                        .notFound()
                        .build();
            }
    
    
            return ResponseEntity
                    .accepted()
                    .body(
                            workflow
                    );
        }
    
        catch (IllegalStateException exception) {
    
            return ResponseEntity
                    .status(
                            HttpStatus.SERVICE_UNAVAILABLE
                    )
                    .body(
                            exception.getMessage()
                    );
        }
    }
}