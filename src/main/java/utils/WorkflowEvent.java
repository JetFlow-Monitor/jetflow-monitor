package utils;

import entities.Job;
import entities.Step;
import entities.WorkflowRun;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Represents an event in the workflow monitoring system.
 * Can be of type WORKFLOW, JOB, STEP, or GENERAL.
 * Provides a method to format the event into a concise log string.
 */
public class WorkflowEvent {

    /** Types of workflow events */
    public enum Type { WORKFLOW, JOB, STEP, GENERAL }

    /** Pattern format for timestamps */
    private static final String PATTERN_FORMAT = "yyyy-MM-dd HH:mm:ss";

    /** Formatter for timestamps in UTC */
    private static final DateTimeFormatter formatter =
            DateTimeFormatter.ofPattern(PATTERN_FORMAT)
                    .withZone(ZoneOffset.UTC);

    /** The type of the event */
    private final Type type;
    /** The repository name */
    private final String repo;
    /** The branch name */
    private final String branch;
    /** The commit SHA */
    private final String sha;
    /** The workflow name */
    private final String workflow;
    /** The workflow run ID */
    private final long runId;
    /** The workflow run status */
    private final String runStatus;
    /** The job name */
    private final String job;
    /** The job status */
    private final String jobStatus;
    /** The step name */
    private final String step;
    /** The step status */
    private final String stepStatus;
    /** The event message */
    private final String message;

    /** Private constructor to initialize all fields */
    private WorkflowEvent(Type type, String repo, String branch, String sha,
                          String workflow, long runId, String runStatus,
                          String job, String jobStatus,
                          String step, String stepStatus,
                          String message) {
        this.type = type;
        this.repo = repo;
        this.branch = branch;
        this.sha = sha;
        this.workflow = workflow;
        this.runId = runId;
        this.runStatus = runStatus;
        this.job = job;
        this.jobStatus = jobStatus;
        this.step = step;
        this.stepStatus = stepStatus;
        this.message = message;
    }

    /** Formats the event into a concise log string */
    public String format() {
        StringBuilder sb = new StringBuilder();

        // 1. Timestamp and Type
        sb.append("[").append(formatter.format(Instant.now())).append("]");
        sb.append(" [").append(type).append("]");

        // 2. Event/Message
        if (message != null) {
            sb.append(" ").append(message).append(":");
        }

        // 3. Repository
        sb.append(" ").append(repo);

        switch (type) {
            case WORKFLOW:
                // Workflow events need run details (branch, SHA, workflow name, ID, status)
                sb.append(" Wf:").append(workflow);
                sb.append("/").append(runId);
                sb.append(" Status:").append(runStatus);
                if (branch != null) sb.append(" Branch:").append(branch);
                if (sha != null) sb.append(" SHA:").append(sha, 0, Math.min(sha.length(), 7));
                break;

            case JOB:
                // Job events need run ID and job details
                sb.append(" Run:").append(runId);
                sb.append(" Job:").append(job);
                sb.append(" Status:").append(jobStatus);
                break;

            case STEP:
                // Step events need run ID, job name, and step details
                sb.append(" Run:").append(runId);
                sb.append(" Job:").append(job);
                sb.append(" Step:").append(step);
                sb.append(" Status:").append(stepStatus);
                break;

            case GENERAL:
                // General events only have time, type, repo, and message.
                // Nothing else is needed.
                break;
        }

        return sb.toString();
    }

    /**
     * Creates a workflow-level event (started, completed)
     * @param eventType Event type (started, completed)
     * @param repo Repository name
     * @param run Workflow run details
     * @return WorkflowEvent instance
     */
    public static WorkflowEvent workflowEvent(String eventType, String repo, WorkflowRun run) {
        return new WorkflowEvent(
                Type.WORKFLOW,
                repo,
                run.branch(),
                run.sha(),
                run.name(),
                run.id(),
                run.status(),
                null, null,
                null, null,
                eventType
        );
    }

    /**
     * Creates a job-level event (started, completed)
     * @param eventType Event type (started, completed)
     * @param repo Repository name
     * @param run Workflow run details
     * @param job Job details
     * @return WorkflowEvent instance
     */
    public static WorkflowEvent jobEvent(String eventType, String repo, WorkflowRun run, Job job) {
        return new WorkflowEvent(
                Type.JOB,
                repo,
                run.branch(),
                run.sha(),
                run.name(),
                run.id(),
                run.status(),
                job.name(),
                job.status(),
                null, null,
                eventType
        );
    }

    /**
     * Creates a step-level event (started, completed)
     * @param eventType Event type (started, completed)
     * @param repo Repository name
     * @param run Workflow run details
     * @param job Job details
     * @param step Step details
     * @return WorkflowEvent instance
     */
    public static WorkflowEvent stepEvent(String eventType, String repo, WorkflowRun run, Job job, Step step) {
        return new WorkflowEvent(
                Type.STEP,
                repo,
                run.branch(),
                run.sha(),
                run.name(),
                run.id(),
                run.status(),
                job.name(),
                job.status(),
                step.name(),
                step.status(),
                eventType
        );
    }

    /**
     * Creates a general event with a custom message
     * @param repo Repository name
     * @param message Custom message
     * @return WorkflowEvent instance
     */
    public static WorkflowEvent generalEvent(String repo, String message) {
        return new WorkflowEvent(
                Type.GENERAL,
                repo,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                null,
                message
        );
    }
}
