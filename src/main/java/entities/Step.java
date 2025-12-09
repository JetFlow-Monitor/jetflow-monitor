package entities;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Step record - contains step details including timestamps.
 * @param name The step name
 * @param status The step status
 * @param conclusion The step conclusion
 * @param startedAt The step start time
 * @param completedAt The step completion time
 */
public record Step(String name, String status, String conclusion, String startedAt, String completedAt) {
    /** Constructs a Step from a JsonNode */
    public Step(JsonNode stepNode) {
        this(
                stepNode.path("name").asText(null),
                stepNode.path("status").asText(null),
                stepNode.path("conclusion").asText(null),
                stepNode.path("started_at").asText(null),
                stepNode.path("completed_at").asText(null)
        );
    }
}
