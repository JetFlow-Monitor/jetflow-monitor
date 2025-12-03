package entities;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Job record - contains job details including timestamps.
 * @param id The job ID
 * @param name The job name
 * @param status The job status
 * @param conclusion The job conclusion
 * @param branch The branch name
 * @param sha The commit SHA
 * @param startedAt The job start time
 * @param completedAt The job completion time
 */
public record Job(long id, String name, String status, String conclusion, String branch, String sha, String startedAt, String completedAt) {
    /** Constructs a Job from a JsonNode */
    public Job(JsonNode jobNode) {
        this(
                jobNode.path("id").asLong(),
                jobNode.path("name").asText(null),
                jobNode.path("status").asText(null),
                jobNode.path("conclusion").asText(null),
                jobNode.path("head_branch").asText(null),
                jobNode.path("head_sha").asText(null),
                jobNode.path("started_at").asText(null),
                jobNode.path("completed_at").asText(null)
        );
    }
}
