package utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Simple state store for persisting monitoring state between runs.
 * Stores state in a JSON file mapping repository names to their state objects.
 */
public class StateStore {
    /** The state file name */
    private static final String STATE_FILE = "monitor_state.json";
    /** The Jackson ObjectMapper instance */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** The in-memory state map */
    private Map<String, ObjectNode> state;

    /** Constructor - loads state from file */
    public StateStore() {
        load();
    }

    /** Loads state from the JSON file */
    private void load() {
        try {
            File f = new File(STATE_FILE);
            if (f.exists()) {
                // Read the state file into the map
                state = MAPPER.readValue(f, MAPPER.getTypeFactory().constructMapType(Map.class, String.class, ObjectNode.class));
            } else {
                // Initialize empty state if file does not exist
                state = new HashMap<>();
            }
        } catch (Exception e) {
            // On error, initialize empty state
            state = new HashMap<>();
        }
    }

    /**
     * Retrieves the state object for a given repository, creating it if absent.
     * @param repo The repository name
     * @return The state ObjectNode for the repository
     */
    public synchronized ObjectNode getRepoState(String repo) {
        return state.computeIfAbsent(repo, k -> MAPPER.createObjectNode());
    }

    /** Saves the current state to the JSON file */
    public synchronized void save() {
        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(new File(STATE_FILE), state);
        } catch (Exception ignored) {}
    }
}
