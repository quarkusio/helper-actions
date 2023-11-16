package io.quarkus.bot.develocity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeSet;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.kohsuke.github.GHCheckRun;
import org.kohsuke.github.GHCheckRunBuilder.Output;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHWorkflowRun;
import org.kohsuke.github.GHWorkflowRun.Conclusion;
import org.kohsuke.github.GitHub;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.githubaction.Action;
import io.quarkiverse.githubaction.Commands;
import io.quarkiverse.githubaction.Context;
import io.quarkiverse.githubaction.Inputs;

public class InjectBuildScansAction {

    private static final String BUILD_SUMMARY_CHECK_RUN_PREFIX = "Build summary for ";
    private static final String WORKFLOW_RUN_ID_MARKER = "<!-- Quarkus-GitHub-Bot/workflow-run-id:%1$s -->";
    private static final String BUILD_SCANS = "Build scans";

    @Inject
    ObjectMapper objectMapper;

    @Action("inject-build-scans")
    void injectBuildScans(Context context, Commands commands, Inputs inputs, GitHub github) {
        Path buildMetadataJson = Path.of("build-metadata.json");
        if (!Files.isReadable(buildMetadataJson)) {
            commands.warning(buildMetadataJson + " is not readable, ignoring");
            return;
        }

        OptionalLong workflowRunIdInput = inputs.getLong("workflow-run-id");
        if (workflowRunIdInput.isEmpty()) {
            commands.warning("No workflow run id provided ignoring");
            return;
        }
        long workflowRunId = workflowRunIdInput.getAsLong();

        BuildScanStatuses statuses;
        try {
            statuses = objectMapper.readValue(buildMetadataJson.toFile(), BuildScanStatuses.class);
        } catch (IOException e) {
            commands.error("Unable to parse " + buildMetadataJson + ": " + e.getMessage());
            return;
        }

        Map<String, String> buildScanMapping = statuses.builds.stream()
                .collect(Collectors.toMap(s -> s.jobName, s -> s.buildScanLink));

        try {
            GHRepository repository = github.getRepository(context.getGitHubRepository());
            GHPullRequest pullRequest = repository.getPullRequest(statuses.prNumber);
            GHWorkflowRun workflowRun = repository.getWorkflowRun(workflowRunId);

            if (workflowRun.getConclusion() == Conclusion.CANCELLED) {
                return;
            }

            updateComment(commands, pullRequest, workflowRun, buildScanMapping);
            updateCheckRun(commands, repository, workflowRun, buildScanMapping);
            createBuildScansOutput(workflowRun, statuses);
        } catch (IOException e) {
            commands.error("Error trying to attach build scans to pull request #" + statuses.prNumber + ": " + e.getMessage());
        }
    }

    private void updateComment(Commands commands, GHPullRequest pullRequest, GHWorkflowRun workflowRun,
            Map<String, String> buildScanMapping) throws IOException {
        List<GHIssueComment> commentsSinceWorkflowRunStarted = pullRequest.queryComments()
                .since(workflowRun.getCreatedAt())
                .list().toList();
        Collections.reverse(commentsSinceWorkflowRunStarted);

        String workflowRunIdMarker = String.format(WORKFLOW_RUN_ID_MARKER, workflowRun.getId());
        Optional<GHIssueComment> reportCommentCandidate = commentsSinceWorkflowRunStarted.stream()
                .filter(c -> c.getBody().contains(workflowRunIdMarker))
                .findFirst();

        if (reportCommentCandidate.isEmpty()) {
            commands.warning("Unable to find a report comment to update");
            return;
        }

        GHIssueComment reportComment = reportCommentCandidate.get();

        String updatedCommentBody = reportComment.getBody().lines().map(line -> {
            for (Entry<String, String> buildScanEntry : buildScanMapping.entrySet()) {
                if (line.contains("| " + buildScanEntry.getKey() + " |")) {
                    return line.replace(":construction:", "[:mag:](" + buildScanEntry.getValue() + ")");
                }
            }
            return line;
        }).collect(Collectors.joining("\n"));

        if (!updatedCommentBody.equals(reportComment.getBody())) {
            reportComment.update(updatedCommentBody);
        }
    }

    private void updateCheckRun(Commands commands, GHRepository repository, GHWorkflowRun workflowRun,
            Map<String, String> buildScanMapping) throws IOException {

        Optional<GHCheckRun> reportCheckRunCandidate = repository.getCheckRuns(workflowRun.getHeadSha()).toList().stream()
                .filter(cr -> cr.getOutput() != null)
                .filter(cr -> cr.getOutput().getTitle().startsWith(BUILD_SUMMARY_CHECK_RUN_PREFIX))
                .filter(cr -> cr.getOutput().getText() != null && !cr.getOutput().getText().isBlank())
                .findAny();

        if (reportCheckRunCandidate.isEmpty()) {
            commands.warning("Unable to find a check run to update");
            return;
        }

        GHCheckRun reportCheckRun = reportCheckRunCandidate.get();

        Output output = new Output(reportCheckRun.getOutput().getTitle(), reportCheckRun.getOutput().getSummary());

        String updatedReportText = reportCheckRun.getOutput().getText().lines().map(line -> {
            for (Entry<String, String> buildScanEntry : buildScanMapping.entrySet()) {
                if (line.contains("| " + buildScanEntry.getKey() + " |")) {
                    return line.replace(":construction:", "[:mag:](" + buildScanEntry.getValue() + ")");
                }
            }
            return line;
        }).collect(Collectors.joining("\n"));

        if (!updatedReportText.equals(reportCheckRun.getOutput().getText())) {
            reportCheckRun.update().add(output).create();
        }
    }

    private void createBuildScansOutput(GHWorkflowRun workflowRun, BuildScanStatuses statuses) throws IOException {
        Output output = new Output(BUILD_SCANS, BUILD_SCANS);

        StringBuilder buildScans = new StringBuilder();
        buildScans.append("| Status | Name | Build scan |\n");
        buildScans.append("| :-:  | --  | :-:  |\n");

        for (BuildScanStatus build : statuses.builds) {
            buildScans.append("| ").append(getConclusionEmoji(build.status)).append(" | ").append(build.jobName)
                    .append(" | [:mag:](").append(build.buildScanLink).append(") |");
        }

        output.withText(buildScans.toString());

        workflowRun.getRepository().createCheckRun(BUILD_SCANS, workflowRun.getHeadSha())
                .add(output)
                .withConclusion(GHCheckRun.Conclusion.NEUTRAL)
                .withCompletedAt(new Date())
                .create();
    }

    private static String getConclusionEmoji(String conclusion) {
        // apparently, conclusion can sometimes be null...
        if (conclusion == null) {
            return ":question:";
        }

        switch (conclusion) {
            case "success":
                return ":heavy_check_mark:";
            case "failure":
                return "✖";
            case "cancelled":
                return ":hourglass:";
            case "skipped":
                return ":no_entry_sign:";
            default:
                return ":question:";
        }
    }

    public static class BuildScanStatuses {

        public int prNumber;

        public TreeSet<BuildScanStatus> builds;
    }

    public static class BuildScanStatus implements Comparable<BuildScanStatus> {

        public String jobName;

        public String status;

        public String buildScanLink;

        @Override
        public int compareTo(BuildScanStatus o) {
            int order1 = getOrder(jobName);
            int order2 = getOrder(o.jobName);

            if (order1 == order2) {
                return jobName.compareToIgnoreCase(o.jobName);
            }

            return order1 - order2;
        }

        @Override
        public String toString() {
            return "BuildScanStatus [jobName=" + jobName + "]";
        }
    }

    private static int getOrder(String jobName) {
        if (jobName.startsWith("Initial JDK")) {
            return 1;
        }
        if (jobName.startsWith("Calculate Test Jobs")) {
            return 2;
        }
        if (jobName.startsWith("JVM Tests - ")) {
            if (jobName.contains("Windows")) {
                return 12;
            }
            return 11;
        }
        if (jobName.startsWith("Maven Tests - ")) {
            if (jobName.contains("Windows")) {
                return 22;
            }
            return 21;
        }
        if (jobName.startsWith("Gradle Tests - ")) {
            if (jobName.contains("Windows")) {
                return 32;
            }
            return 31;
        }
        if (jobName.startsWith("Devtools Tests - ")) {
            if (jobName.contains("Windows")) {
                return 42;
            }
            return 41;
        }
        if (jobName.startsWith("Kubernetes Tests - ")) {
            if (jobName.contains("Windows")) {
                return 52;
            }
            return 51;
        }
        if (jobName.startsWith("Quickstarts Compilation")) {
            return 61;
        }
        if (jobName.startsWith("MicroProfile TCKs Tests")) {
            return 71;
        }
        if (jobName.startsWith("Native Tests - ")) {
            if (jobName.contains("Windows")) {
                return 82;
            }
            return 81;
        }

        return 200;
    }
}
