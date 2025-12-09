package entities;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * WorkflowRun record - contains workflow run details including timestamps.
 * @param id The workflow run ID
 * @param name The workflow run name
 * @param status The workflow run status
 * @param conclusion The workflow run conclusion
 * @param htmlUrl The HTML URL of the workflow run
 * @param branch The branch name
 * @param sha The commit SHA
 * @param runStartedAt The workflow run start time
 * @param updatedAt The workflow run update time
 * @param createdAt The workflow run creation time
 */
public record WorkflowRun(long id, String name, String status, String conclusion,
                          String htmlUrl, String branch, String sha, String runStartedAt, String updatedAt, String createdAt) {
    /** Constructs a WorkflowRun from a JsonNode */
    public WorkflowRun(JsonNode runNode) {
        this(
                runNode.path("id").asLong(),
                runNode.path("name").asText(null),
                runNode.path("status").asText(null),
                runNode.path("conclusion").asText(null),
                runNode.path("html_url").asText(null),
                runNode.path("head_branch").asText(null),
                runNode.path("head_sha").asText(null),
                runNode.path("run_started_at").asText(null),
                runNode.path("updated_at").asText(null),
                runNode.path("created_at").asText(null)
        );
    }
}
