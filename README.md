# JetFlow: GitHub Actions Monitor CLI

**JetFlow** is a command-line utility built in Java designed to provide real-time monitoring of GitHub Workflow runs. It polls the GitHub Actions API regularly and reports workflow, job, and step transitions to the console in a concise, human-readable format.

The name **JetFlow** combines **"Jet"** (representing the speed and intelligence required, nodding to **JetBrains**) with **"Flow"** (representing the continuous stream of events from CI/CD workflows).

## Features

  * **Real-time Event Reporting:** Reports `workflow.queued`, `job.started`, `step.completed`, and all other lifecycle transitions immediately to `stdout`.
  * **State Persistence:** Stores the last known state of workflows in a local file (`monitor_state.json`), enabling accurate transition reporting even after the tool is restarted.
  * **Historical Event Catch-up:** On restart, it replays any missed completion events that occurred since the last poll cycle, fulfilling the guarantee of continuous information flow.
  * **Concise Output:** Uses a structured, contextual format to ensure log lines are short, readable, and focused on the event type.
  * **Graceful Termination:** Handles CLI interruption (`Ctrl+C`) cleanly, ensuring state is saved and all polling threads are stopped instantly.
  * **Pagination Handling:** Loops through API pages for both runs and jobs to ensure no data is missed, even in high-volume repositories (matrix builds or long outages).

-----

## Setup and Building

### Prerequisites

1.  **JDK 17+**
2.  **Maven**

### Building the Executable

From the root directory of the project:

```bash
# Compile and package the application into an executable JAR
mvn clean package -DskipTests
```

This command generates a fat JAR named `jetflow-1.0-SNAPSHOT-shaded.jar` in the `target/` directory.

### Generating a GitHub Token

JetFlow requires a **GitHub Personal Access Token (PAT)** with appropriate permissions to read workflow data.

1.  Go to **GitHub Settings** -\> **Developer Settings** -\> **Personal access tokens**.
2.  Click **Generate new token (classic)**.
3.  Set an expiration date (or choose No expiration).
4.  **Crucially, check the `repo` scope.** This allows the tool to read private workflow runs, jobs, and steps.
5.  Save the generated token securely.

-----

## How to Run and Test

JetFlow uses the `monitor` subcommand and requires the repository in `owner/repo` format and the token as arguments.

### **Step 1: The Cold Start (Silent Baseline)**

We will run JetFlow for the first time. Since the state file doesn't exist, JetFlow will connect, build a baseline state of all current workflows, and **remain silent** as per the design requirement: "only report new events."

1.  **Target Repository:** You need a repository with existing workflow runs. (Use the test repo you created: `[Test Repo Owner]/jetflow-test-repo`).

2.  **Run the command:**

    ```bash
    java -jar target/jetflow-1.0-SNAPSHOT-shaded.jar monitor [owner]/jetflow-test-repo <YOUR_PAT>
    ```

3.  **Expected Output:**
    The CLI will confirm connection, load the initial state, and then output nothing, waiting for a transition.

    ```
    Monitoring repository: [owner]/jetflow-test-repo
    Using token: ****....
    ✓ Connected to GitHub.
    ✓ Initial state snapshot taken.
    Waiting for new workflow events... (Trigger a run to test)
    ```

### **Step 2: Triggering a New Workflow (Real-time Flow)**

Now that JetFlow is running, trigger a new workflow run on your test repository via the GitHub UI or an API call.

1.  **Go to GitHub:** Navigate to the **Actions** tab of your test repository.
2.  **Run Workflow:** Manually dispatch a new run.
3.  **Expected Output (Real-time):**
    The console will immediately print the sequence of events as they happen:
    ```
    [10:05:30.123] [WORKFLOW] workflow.queued: [owner]/jetflow-test-repo Wf:main.yml/123456 Status:queued Branch:main SHA:5f7a23c
    [10:05:40.456] [JOB] job.started: [owner]/jetflow-test-repo Run:123456 Job:build Status:in_progress
    [10:05:41.789] [STEP] step.started: [owner]/jetflow-test-repo Run:123456 Job:build Step:Set up job Status:in_progress
    [10:05:45.990] [STEP] step.completed: [owner]/jetflow-test-repo Run:123456 Job:build Step:Checkout Status:completed
    # ... more steps ...
    [10:06:10.111] [JOB] job.completed: [owner]/jetflow-test-repo Run:123456 Job:build Status:completed
    [10:06:11.222] [WORKFLOW] workflow.completed: [owner]/jetflow-test-repo Wf:main.yml/123456 Status:completed Branch:main SHA:5f7a23c
    ```

### **Step 3: Testing Graceful Termination (Ctrl+C)**

While the tool is running, press **`Ctrl+C`** in the terminal.

1.  **Expected Output:**
    The tool immediately catches the shutdown signal, saves its final state, and terminates without waiting for the next poll cycle:
    ```
    ^C
    Shutting down monitoring service...
    Monitoring stopped safely.
    ```
2.  **Check State:** A file named `monitor_state.json` should now exist in the directory, containing the final status of all runs seen.

### **Step 4: Testing Missed Events (Catch-up)**

Now, simulate a period where the tool was offline but the CI/CD pipeline was running.

1.  **Wait and Trigger:** Leave the tool *stopped*. Go to GitHub and manually trigger **two or three new workflows** and let them complete.

2.  **Restart JetFlow:** Run the same command as Step 1.

    ```bash
    java -jar target/jetflow-1.0-SNAPSHOT.jar monitor [owner]/jetflow-test-repo <YOUR_PAT>
    ```

3.  **Expected Output (Catch-up):**
    Because the runs completed while the tool was off, JetFlow detects the gap by comparing `updated_at` timestamps against the `_last_checked_at` from `monitor_state.json`. It immediately replays and prints the completion events (and potentially job/step events) that were missed:

    ```
    # Tool starts...
    [10:15:20.333] [WORKFLOW] workflow.completed: [owner]/jetflow-test-repo Wf:main.yml/123457 Status:completed Branch:main SHA:6g8b34d
    [10:15:25.666] [WORKFLOW] workflow.completed: [owner]/jetflow-test-repo Wf:main.yml/123458 Status:completed Branch:main SHA:7h9c45e
    # Tool goes silent and waits for real-time updates again.
    ```

-----

## Implementation Details and Technical Deep Dive

The core challenge was efficiently monitoring state changes across a slow, external API while handling concurrency and persistence.

### 1\. Concurrency and Threading

The `MonitorService` employs two dedicated Java `ExecutorService` instances:

  * **`poller` (ScheduledExecutorService):** This single-threaded executor is responsible for the API calls. It runs the heavy `performPollCycle()` logic on a fixed schedule.
  * **`printer` (ExecutorService):** This single-threaded executor manages all console output. All log lines are placed onto a `BlockingQueue<String>`. This design ensures that slow API responses **never block** the console output, and, conversely, console output never holds up the next API poll.
  * **Graceful Shutdown:** The `stop()` method uses `poller.shutdownNow()` and crucially, `printer.shutdownNow()`. Using `shutdownNow()` forces the interruption of the blocking `outQueue.take()` call.

### 2\. State Management and Logic

The "New Events Only" and "Catch-up" requirements are handled by `StateStore.java` and logic inside `performPollCycle()`:

1.  **`_last_checked_at`:** Stored in `monitor_state.json`, this timestamp marks the exact moment of the last successful poll.
2.  **First Run:** If `_last_checked_at` is null, the tool fetches Page 1, saves the current state of runs, and explicitly skips event generation (`continue`), establishing a quiet baseline.
3.  **Subsequent Runs:** The tool fetches workflow runs page-by-page. It compares the run's `updated_at` against `_last_checked_at`. If `updated_at > _last_checked_at`, the run is processed, and its status/conclusion changes are re-evaluated and printed.

### 3\. API Reliability and Pagination

The tool is designed to be robust against API volume:

  * **Workflow Run Pagination:** `performPollCycle()` iterates pages of workflow runs. The loop continues until the tool finds a run that was *created* before the `_last_checked_at` timestamp, ensuring all recently updated or created runs are processed.
  * **Job Pagination:** `processJobsForRun()` also includes a pagination loop. This is vital for **matrix strategy** workflows, which often generate hundreds of jobs, easily exceeding GitHub's default 50 jobs per page limit.
  * **Error Handling:** Includes explicit handling for common HTTP errors like `401` (Bad Token), `404` (Repo Not Found), and `403` (Rate Limit), providing clear user feedback and stopping the service immediately for unrecoverable errors.
