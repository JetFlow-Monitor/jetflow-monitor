package services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import entities.Job;
import entities.Step;
import entities.WorkflowRun;
import utils.StateStore;
import utils.WorkflowEvent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * Service to monitor a GitHub repository's Actions workflows, jobs, and steps.
 * Periodically polls the GitHub API for changes and outputs events to console.
 */
public class MonitorService {

    /** ISO Instant formatter in UTC */
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

    /** The repository to monitor (e.g., "owner/repo") */
    private final String repository;
    /** The GitHub Personal Access Token for authentication */
    private final String token;
    /** The HTTP client for making API requests */
    private final HttpClient http;
    /** The state store for persisting last known states */
    private final ObjectMapper MAPPER = new ObjectMapper();
    /** The state store for persisting last known states */
    private final StateStore store;

    /** The scheduled executor for polling */
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "poller");
        t.setDaemon(false);
        return t;
    });
    /** The executor for printing output */
    private final ExecutorService printer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "printer");
        t.setDaemon(false);
        return t;
    });

    /** The output queue for events */
    private final BlockingQueue<String> outQueue = new LinkedBlockingQueue<>();
    /** Indicates if the service is currently running */
    private volatile boolean running = false;
    /** Latch to signal when stopped */
    private final CountDownLatch stopped = new CountDownLatch(1); // for graceful shutdown

    /**
     * Constructs a MonitorService for the specified repository and token.
     * @param repository The repository to monitor (e.g., "owner/repo")
     * @param token The GitHub Personal Access Token for authentication
     */
    public MonitorService(String repository, String token) {
        this.repository = Objects.requireNonNull(repository);
        this.token = Objects.requireNonNull(token);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Constants.HTTP_CONNECT_TIMEOUT_SECONDS))
                .build();
        this.store = new StateStore();

        validateRepoAndToken();
    }

    /**
     * Starts the monitoring service with the specified polling interval.
     * @param intervalSeconds The polling interval in seconds
     */
    public void start(int intervalSeconds) {
        if (running) return;
        running = true;

        // Start printer thread
        printer.submit(() -> {
            try {
                while (running || !outQueue.isEmpty()) {
                    String line = outQueue.take();
                    System.out.println(line);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                outQueue.forEach(System.out::println);
            }
        });

        // Start poller thread
        poller.scheduleAtFixedRate(() -> {
            try {
                performPollCycle();
            } catch (Throwable t) {
                push(WorkflowEvent.generalEvent(repository,
                        "poll error" + t.getMessage().replace("\"", "'")));
            }
        }, 0, Math.max(Constants.MIN_POLLER_PERIOD, intervalSeconds), TimeUnit.SECONDS);
    }

    /** Stops the monitoring service gracefully. */
    public void stop() {
        running = false;
        poller.shutdownNow();
        printer.shutdown();
        try {
            // Wait for termination
            if (!poller.awaitTermination(5, TimeUnit.SECONDS)) poller.shutdownNow();
            if (!printer.awaitTermination(5, TimeUnit.SECONDS)) printer.shutdownNow();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            // Signal stopped
            stopped.countDown();
            System.out.println("Monitoring stopped safely.");
        }
    }

    /** Performs a single polling cycle to check for workflow run updates. */
    private void performPollCycle() throws Exception {
        ObjectNode repoState = store.getRepoState(repository);
        Instant lastPollInstant = Instant.now();

        // Determine if this is the first time we are seeing this repo
        Instant lastChecked = repoState.has(Constants.STATE_KEY_LAST_CHECKED) ?
                Instant.parse(repoState.path(Constants.STATE_KEY_LAST_CHECKED).asText()) : null;

        int page = 1;
        boolean keepFetching = true;

        while (keepFetching && running) {
            // Fetch page of runs
            String url = String.format(Constants.GITHUB_API + Constants.PATH_RUNS,
                    repository, Constants.PAGE_SIZE, page);

            // API Request
            HttpResponse<String> resp = sendGet(url);
            if (resp.statusCode() != Constants.HTTP_OK) {
                handleNonOk(resp);
                break;
            }

            // Parse response
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode runs = root.path(Constants.KEY_WORKFLOW_RUNS);

            if (!runs.isArray() || runs.isEmpty()) {
                break; // No more data
            }

            // Process this batch
            boolean foundOldRun = processRunBatch(runs, repoState, lastChecked);

            // Determine if we should continue fetching
            if (lastChecked == null || foundOldRun || page >= Constants.MAX_PAGES) {
                keepFetching = false;
            } else {
                page++;
            }
        }

        // Update last checked timestamp after cycle completes
        repoState.put(Constants.STATE_KEY_LAST_CHECKED, ISO.format(lastPollInstant));
        store.save();
    }

    /**
     * Processes a batch of workflow runs and detects changes.
     * @param runs The JSON array of workflow runs
     * @param repoState The repository state object node
     * @param lastChecked The timestamp of the last check
     * @return true if old data was encountered, false otherwise
     */
    private boolean processRunBatch(JsonNode runs, ObjectNode repoState, Instant lastChecked) {
        boolean encounteredOldData = false;

        for (JsonNode runNode : runs) {
            // Parse run
            WorkflowRun run = new WorkflowRun(runNode);
            String runIdStr = String.valueOf(run.id());

            // Parse timestamps
            Instant runUpdatedAt = runNode.hasNonNull(Constants.KEY_UPDATED_AT)
                    ? Instant.parse(runNode.path(Constants.KEY_UPDATED_AT).asText()) : Instant.now();
            Instant runCreatedAt = runNode.hasNonNull(Constants.KEY_CREATED_AT)
                    ? Instant.parse(runNode.path(Constants.KEY_CREATED_AT).asText()) : Instant.now();

            boolean known = repoState.has(runIdStr);

            // If first time seeing this repo, save state and skip processing
            if (lastChecked == null) {
                if (!known) {
                    saveRunState(run, repoState);
                }
                continue;
            }

            // Check if run is older than last checked
            if (runCreatedAt.isBefore(lastChecked)) {
                encounteredOldData = true;
            }

            // Skip processing if run not updated since last check
            if (runUpdatedAt.isBefore(lastChecked)) {
                continue;
            }

            // Process run
            ObjectNode runState = repoState.has(runIdStr) ?
                    (ObjectNode) repoState.get(runIdStr) : MAPPER.createObjectNode();
            processRunTransitions(run, runState);
            processJobsForRun(run, runState);

            // Save updated run state
            repoState.set(runIdStr, runState);
        }
        return encounteredOldData;
    }

    /**
     * Saves the current state of a workflow run.
     * @param run The WorkflowRun object
     * @param repoState The repository state object node
     */
    private void saveRunState(WorkflowRun run, ObjectNode repoState) {
        ObjectNode runState = MAPPER.createObjectNode();
        runState.put(Constants.STATE_KEY_STATUS, run.status());
        if (run.conclusion() != null) runState.put(Constants.STATE_KEY_CONCLUSION, run.conclusion());
        repoState.set(String.valueOf(run.id()), runState);
    }

    /**
     * Processes status transitions for a workflow run and generates events.
     * @param run The WorkflowRun object
     * @param runState The run state object node
     */
    private void processRunTransitions(WorkflowRun run, ObjectNode runState) {
        // Detect run status transitions
        String prevStatus = runState.has(Constants.STATE_KEY_STATUS) ?
                runState.path(Constants.STATE_KEY_STATUS).asText(null) : null;
        String prevConclusion = runState.has(Constants.STATE_KEY_CONCLUSION) ?
                runState.path(Constants.STATE_KEY_CONCLUSION).asText(null) : null;

        String currStatus = run.status();
        String currConclusion = run.conclusion();

        if (!Objects.equals(prevStatus, currStatus) || !Objects.equals(prevConclusion, currConclusion)) {
            if (Constants.STATUS_QUEUED.equalsIgnoreCase(currStatus)) {
                push(WorkflowEvent.workflowEvent(Constants.EVENT_WORKFLOW_QUEUED, repository, run));
            } else if (Constants.STATUS_IN_PROGRESS.equalsIgnoreCase(currStatus)) {
                push(WorkflowEvent.workflowEvent(Constants.EVENT_WORKFLOW_STARTED, repository, run));
            } else if (Constants.STATUS_COMPLETED.equalsIgnoreCase(currStatus)) {
                push(WorkflowEvent.workflowEvent(Constants.EVENT_WORKFLOW_COMPLETED, repository, run));
            }

            // Update state
            runState.put(Constants.STATE_KEY_STATUS, currStatus == null ? "" : currStatus);
            if (currConclusion != null) runState.put(Constants.STATE_KEY_CONCLUSION, currConclusion);
        }
    }

    /**
     * Processes jobs for a given workflow run and detects changes.
     * @param run The WorkflowRun object
     * @param runState The run state object node
     */
    private void processJobsForRun(WorkflowRun run, ObjectNode runState) {
        // Retrieve or create jobs state
        ObjectNode jobsState = runState.has(Constants.STATE_KEY_JOBS) ?
                (ObjectNode) runState.get(Constants.STATE_KEY_JOBS) : MAPPER.createObjectNode();

        // Paginate through jobs
        int page = 1;
        boolean moreJobs = true;

        while (moreJobs) {
            try {
                // Fetch jobs for the run
                String jobsUrl = String.format(Constants.GITHUB_API + Constants.PATH_JOBS,
                        repository, run.id(), Constants.PAGE_SIZE, page);

                // API Request
                HttpResponse<String> resp = sendGet(jobsUrl);
                if (resp.statusCode() != Constants.HTTP_OK) {
                    handleNonOk(resp);
                    return;
                }

                // Parse response
                JsonNode root = MAPPER.readTree(resp.body());
                JsonNode jobs = root.path(Constants.KEY_JOBS);

                // No more jobs
                if (!jobs.isArray() || jobs.isEmpty()) {
                    moreJobs = false;
                    continue;
                }

                for (JsonNode jobNode : jobs) {
                    processSingleJob(jobNode, run, jobsState);
                }

                // Check if we need to fetch more pages
                if (jobs.size() < Constants.PAGE_SIZE) {
                    moreJobs = false;
                } else {
                    page++;
                    // Limit max pages per poll cycle
                    if (page > Constants.MAX_JOB_PAGES) moreJobs = false;
                }
            } catch (Exception e) {
                push(WorkflowEvent.generalEvent(repository, "jobs processing error: " + e.getMessage()));
                moreJobs = false;
            }
        }
        runState.set(Constants.STATE_KEY_JOBS, jobsState);
    }

    /**
     * Processes a single job and its steps, detecting changes and generating events.
     * @param jobNode The JSON node representing the job
     * @param run The WorkflowRun object
     * @param jobsState The jobs state object node
     */
    private void processSingleJob(JsonNode jobNode, WorkflowRun run, ObjectNode jobsState) {
        // Parse job
        Job job = new Job(jobNode);
        String jobIdStr = String.valueOf(job.id());
        ObjectNode jobState = jobsState.has(jobIdStr) ?
                (ObjectNode) jobsState.get(jobIdStr) : MAPPER.createObjectNode();

        // Detect job status transitions
        String prevJobStatus = jobState.has(Constants.STATE_KEY_STATUS) ?
                jobState.path(Constants.STATE_KEY_STATUS).asText(null) : null;
        String prevJobConclusion = jobState.has(Constants.STATE_KEY_CONCLUSION) ?
                jobState.path(Constants.STATE_KEY_CONCLUSION).asText(null) : null;
        String currJobStatus = job.status();
        String currJobConclusion = job.conclusion();

        if (!Objects.equals(prevJobStatus, currJobStatus) ||
                !Objects.equals(prevJobConclusion, currJobConclusion)) {
            if (Constants.STATUS_QUEUED.equalsIgnoreCase(currJobStatus)) {
                push(WorkflowEvent.jobEvent(Constants.EVENT_JOB_QUEUED, repository, run, job));
            } else if (Constants.STATUS_IN_PROGRESS.equalsIgnoreCase(currJobStatus)) {
                push(WorkflowEvent.jobEvent(Constants.EVENT_JOB_STARTED, repository, run, job));
            } else if (Constants.STATUS_COMPLETED.equalsIgnoreCase(currJobStatus)) {
                push(WorkflowEvent.jobEvent(Constants.EVENT_JOB_COMPLETED, repository, run, job));
            }

            jobState.put(Constants.STATE_KEY_STATUS, currJobStatus == null ? "" : currJobStatus);
            if (currJobConclusion != null) jobState.put(Constants.STATE_KEY_CONCLUSION, currJobConclusion);
        }

        // Process steps within the job
        JsonNode steps = jobNode.path(Constants.KEY_STEPS);
        if (steps.isArray()) {
            // Retrieve or create steps state
            ObjectNode stepsState = jobState.has(Constants.STATE_KEY_STEPS) ?
                    (ObjectNode) jobState.get(Constants.STATE_KEY_STEPS) : MAPPER.createObjectNode();

            for (int i = 0; i < steps.size(); i++) {
                JsonNode stepNode = steps.get(i);
                if (stepNode == null || stepNode.isNull()) continue;

                // Parse step
                Step step = new Step(stepNode);
                String stepKey = "s" + i + "-" + sanitize(step.name());

                String stepStateKeyStatus = stepKey + "." + Constants.STATE_KEY_STATUS;
                String stepStateKeyConclusion = stepKey + "." + Constants.STATE_KEY_CONCLUSION;

                // Detect step status transitions
                String prevStepStatus = stepsState.has(stepStateKeyStatus) ?
                        stepsState.path(stepStateKeyStatus).asText(null) : null;
                String currStepStatus = step.status();
                String prevConclusion = stepsState.has(stepStateKeyConclusion) ?
                        stepsState.path(stepStateKeyConclusion).asText(null) : null;
                String currConclusion = step.conclusion();

                if (!Objects.equals(prevStepStatus, currStepStatus) ||
                        !Objects.equals(prevConclusion, currConclusion)) {
                    if (Constants.STATUS_IN_PROGRESS.equalsIgnoreCase(currStepStatus)) {
                        push(WorkflowEvent.stepEvent(Constants.EVENT_STEP_STARTED, repository, run, job, step));
                    }
                    if (Constants.STATUS_COMPLETED.equalsIgnoreCase(currStepStatus) ||
                            (currConclusion != null && !currConclusion.isEmpty())) {
                        push(WorkflowEvent.stepEvent(Constants.EVENT_STEP_COMPLETED, repository, run, job, step));
                    }

                    stepsState.put(stepStateKeyStatus, currStepStatus == null ? "" : currStepStatus);
                    if (currConclusion != null) stepsState.put(stepStateKeyConclusion, currConclusion);
                }
            }
            // Update job state with steps state
            jobState.set(Constants.STATE_KEY_STEPS, stepsState);
        }
        // Update jobs state with job state
        jobsState.set(jobIdStr, jobState);
    }

    /**
     * Handles non-OK HTTP responses from the GitHub API.
     * @param resp The HTTP response
     * @throws InterruptedException if interrupted during backoff
     */
    private void handleNonOk(HttpResponse<String> resp) throws InterruptedException {
        int code = resp.statusCode();
        // Handle specific HTTP error codes
        if (code == Constants.HTTP_UNAUTHORIZED) {
            push(WorkflowEvent.generalEvent(repository, "Unauthorized (401). Check token."));
            stop();
        } else if (code == Constants.HTTP_FORBIDDEN_RATE_LIMIT) {
            String remain = resp.headers().firstValue(Constants.KEY_RATE_LIMIT_REMAINING).orElse("?");
            push(WorkflowEvent.generalEvent(repository, "Rate Limited (403). Remaining: " + remain));
            Thread.sleep(Constants.RATE_LIMIT_BACKOFF_MS); // Backoff to avoid hammering
        } else if (code == Constants.HTTP_NOT_FOUND) {
            push(WorkflowEvent.generalEvent(repository, "Repo not found (404)."));
            stop();
        } else {
            // Generic error
            push(WorkflowEvent.generalEvent(repository, "HTTP " + code));
        }
    }

    /**
     * Sends a GET request to the specified URL with authentication headers.
     * @param url The URL to send the GET request to
     * @return The HTTP response
     * @throws IOException if an I/O error occurs
     * @throws InterruptedException if the operation is interrupted
     */
    private HttpResponse<String> sendGet(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .header("Accept", Constants.GITHUB_API_ACCEPT_HEADER)
                .header("Authorization", Constants.AUTHORIZATION_HEADER_PREFIX + token)
                .timeout(Duration.ofSeconds(Constants.HTTP_REQUEST_TIMEOUT_SECONDS))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** Validates the repository and token by making a test API call. */
    private void validateRepoAndToken() {
        try {
            HttpResponse<String> resp = sendGet(String.format(Constants.GITHUB_API + Constants.PATH_REPO_VALIDATION, repository));
            if (resp.statusCode() != Constants.HTTP_OK) {
                throw new IllegalArgumentException("Repo access failed: HTTP " + resp.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            throw new IllegalArgumentException("Repo validation error", e);
        }
    }

    /** Pushes a formatted line to the output queue. */
    private void push(String line) {
        outQueue.offer(line);
    }

    /** Pushes a WorkflowEvent to the output queue after formatting. */
    private void push(WorkflowEvent fe) {
        push(fe.format());
    }

    /** Sanitizes a string to be used as a key by replacing non-alphanumeric characters. */
    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    /** Constants used in the MonitorService */
    private static final class Constants {

        // --- NUMERIC CONSTANTS ---

        /** Maximum number of pages to fetch per poll cycle. */
        private static final int MAX_PAGES = 10;
        /** Number of seconds for minimum poller period */
        private static final int PAGE_SIZE = 50;
        /** Minimum poller period in seconds */
        private static final int MAX_JOB_PAGES = 5;

        private static final int MIN_POLLER_PERIOD = 1;

        /** Rate limit backoff duration in milliseconds */
        private static final int RATE_LIMIT_BACKOFF_MS = 10000;
        /** HTTP client connection timeout in seconds */
        private static final int HTTP_CONNECT_TIMEOUT_SECONDS = 10;
        /** HTTP request timeout in seconds */
        private static final int HTTP_REQUEST_TIMEOUT_SECONDS = 20;
        /** HTTP unauthorized status code */
        private static final int HTTP_UNAUTHORIZED = 401;
        /** HTTP forbidden status code for rate limiting */
        private static final int HTTP_FORBIDDEN_RATE_LIMIT = 403;
        /** HTTP not found status code */
        private static final int HTTP_NOT_FOUND = 404;
        /** HTTP OK status code */
        private static final int HTTP_OK = 200;


        // --- STRING CONSTANTS ---

        /** Base GitHub API URL */
        private static final String GITHUB_API = "https://api.github.com";
        /** GitHub API Accept header for v3 */
        private static final String GITHUB_API_ACCEPT_HEADER = "application/vnd.github.v3+json";
        /** Authorization header prefix */
        private static final String AUTHORIZATION_HEADER_PREFIX = "token ";
        /** Runs API path */
        private static final String PATH_RUNS = "/repos/%s/actions/runs?per_page=%d&page=%d";
        /** Jobs API path */
        private static final String PATH_JOBS = "/repos/%s/actions/runs/%d/jobs?per_page=%d&page=%d";
        /** Repo validation API path */
        private static final String PATH_REPO_VALIDATION = "/repos/%s";

        /** Key for workflow runs in API response */
        private static final String KEY_WORKFLOW_RUNS = "workflow_runs";
        /** Key for jobs in API response */
        private static final String KEY_JOBS = "jobs";
        /** Key for steps in API response */
        private static final String KEY_STEPS = "steps";
        /** Key for rate limit remaining header */
        private static final String KEY_RATE_LIMIT_REMAINING = "X-RateLimit-Remaining";
        /** Key for updated_at field */
        private static final String KEY_UPDATED_AT = "updated_at";
        /** Key for created_at field */
        private static final String KEY_CREATED_AT = "created_at";

        /** Last checked timestamp state key */
        private static final String STATE_KEY_LAST_CHECKED = "_last_checked_at";
        /** Status and Conclusion State Keys */
        private static final String STATE_KEY_STATUS = "_status";
        /** Conclusion state key */
        private static final String STATE_KEY_CONCLUSION = "_conclusion";
        /** Jobs and Steps State Keys */
        private static final String STATE_KEY_JOBS = "_jobs";
        /** Steps state key */
        private static final String STATE_KEY_STEPS = "_steps";

        /** Queued status */
        private static final String STATUS_QUEUED = "queued";
        /** In Progress status */
        private static final String STATUS_IN_PROGRESS = "in_progress";
        /** Completed status */
        private static final String STATUS_COMPLETED = "completed";

        /** Workflow queued event */
        private static final String EVENT_WORKFLOW_QUEUED = "workflow.queued";
        /** Workflow started event */
        private static final String EVENT_WORKFLOW_STARTED = "workflow.started";
        /** Workflow completed event */
        private static final String EVENT_WORKFLOW_COMPLETED = "workflow.completed";
        /** Job queued event */
        private static final String EVENT_JOB_QUEUED = "job.queued";
        /** Job started event */
        private static final String EVENT_JOB_STARTED = "job.started";
        /** Job completed event */
        private static final String EVENT_JOB_COMPLETED = "job.completed";
        /** Step started event */
        private static final String EVENT_STEP_STARTED = "step.started";
        /** Step completed event */
        private static final String EVENT_STEP_COMPLETED = "step.completed";
    }
}
