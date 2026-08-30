/*
 * The MIT License
 *
 * Copyright 2026 Olivier Lamy
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.jenkins.plugins.github_pr_approval;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Item;
import hudson.model.Job;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import jenkins.branch.MultiBranchProject;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.variant.OptionalExtension;

/**
 * Exposes the pending fork pull requests as MCP tools, so an assistant can list what is waiting and
 * approve it without opening the Jenkins UI.
 *
 * <p>Only registers when the MCP Server plugin is installed ({@link OptionalExtension}); the plugin
 * works the same without it. Every call runs as the MCP caller, so the same permissions the web UI
 * enforces apply here too.
 */
@OptionalExtension(requirePlugins = "mcp-server")
public class PendingApprovalMcpTools implements McpServerExtension {

    /** One fork pull request waiting for approval. */
    public record PendingApproval(
            String jobFullName,
            int prNumber,
            String prAuthor,
            @CheckForNull String currentPullHash,
            boolean requireApprovalForNewCommits,
            String url) {}

    /** What {@link #approvePullRequests} did for one job. {@code error} is set only when it failed. */
    public record ApprovalResult(
            String jobFullName,
            int prNumber,
            String prAuthor,
            boolean approved,
            boolean authorAutoApprovalAdded,
            @CheckForNull String error) {}

    @Tool(
            description = "Lists the GitHub fork pull requests that are blocked waiting for external "
                    + "approval before they can build. Scans every multibranch project, or just one "
                    + "when its full name is given.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false))
    @SuppressWarnings("rawtypes")
    public List<PendingApproval> getPendingApprovals(
            @Nullable
                    @ToolParam(
                            description = "Full name of a multibranch project to limit the search to, "
                                    + "e.g. 'my-org-repo'; omit to scan them all",
                            required = false)
                    String jobName) {
        List<PendingApproval> pending = new ArrayList<>();
        for (MultiBranchProject project : projectsToScan(jobName)) {
            for (Object child : project.getItems()) {
                if (!(child instanceof Job)) {
                    continue;
                }
                Job<?, ?> job = (Job<?, ?>) child;
                ExternalApprovalInfo info = ExternalApprovalHelper.getApprovalInfo(job);
                if (info != null && PendingApprovalAction.isBlocked(job, info)) {
                    pending.add(new PendingApproval(
                            job.getFullName(),
                            info.prNumber,
                            info.prAuthor,
                            info.currentPullHash,
                            info.requireApprovalForNewCommits,
                            url(job)));
                }
            }
        }
        return pending;
    }

    /**
     * The multibranch projects to look through: the one named, or all of them when no name is given.
     * Either way {@code getItemByFullName} / {@code getAllItems} filter by {@code Item.READ}, so a
     * caller only ever sees projects they may read. An unknown name yields nothing.
     */
    @SuppressWarnings("rawtypes")
    private static List<MultiBranchProject> projectsToScan(@Nullable String jobName) {
        if (jobName == null || jobName.isBlank()) {
            return Jenkins.get().getAllItems(MultiBranchProject.class);
        }
        MultiBranchProject project = Jenkins.get().getItemByFullName(jobName, MultiBranchProject.class);
        return project == null ? List.of() : List.of(project);
    }

    @Tool(
            description = "Approves blocked GitHub fork pull requests so they can build. Optionally adds "
                    + "each pull request author to the policy's auto-approval users, so their future "
                    + "pull requests build without asking. Each job is reported on independently: a bad "
                    + "job name does not stop the others.",
            annotations = @Tool.Annotations(destructiveHint = false, idempotentHint = true))
    public List<ApprovalResult> approvePullRequests(
            @ToolParam(description = "Full names of the branch jobs, e.g. 'my-org-repo/PR-42'")
                    List<String> jobFullNames,
            @Nullable
                    @ToolParam(
                            description = "Also add each pull request author to the auto-approval users list",
                            required = false)
                    Boolean addAuthorToAutoApprovalUsers)
            throws IOException {
        boolean addAuthor = Boolean.TRUE.equals(addAuthorToAutoApprovalUsers);
        List<ApprovalResult> results = new ArrayList<>();
        for (String jobFullName : jobFullNames) {
            results.add(approveOne(jobFullName, addAuthor));
        }
        return results;
    }

    /**
     * Approves one job. An unknown or ineligible job comes back as a failed {@link ApprovalResult}
     * rather than an exception, so one bad entry does not abort the whole batch. A permission failure
     * still throws: authorisation stays loud and is never hidden in the result.
     */
    private ApprovalResult approveOne(String jobFullName, boolean addAuthor) throws IOException {
        Job<?, ?> job = Jenkins.get().getItemByFullName(jobFullName, Job.class);
        if (job == null) {
            return new ApprovalResult(jobFullName, 0, null, false, false, "No such job: " + jobFullName);
        }
        ExternalApprovalInfo info = ExternalApprovalHelper.getApprovalInfo(job);
        if (info == null) {
            return new ApprovalResult(
                    jobFullName,
                    0,
                    null,
                    false,
                    false,
                    "Not a fork pull request under the external-approval policy: " + jobFullName);
        }
        job.checkPermission(Item.CONFIGURE);

        boolean added = false;
        if (addAuthor) {
            // Editing the policy is a project-config change; hold the caller to that permission.
            if (info.context != null) {
                info.context.checkPermission(Item.CONFIGURE);
            }
            added = ExternalApprovalHelper.addAutoApprovalUser(job, info.prAuthor);
        }

        boolean approved = PendingApprovalAction.approve(
                job, info.currentPullHash, Jenkins.get().getAuthentication2().getName());
        return new ApprovalResult(job.getFullName(), info.prNumber, info.prAuthor, approved, added, null);
    }

    private static String url(Job<?, ?> job) {
        String root = Jenkins.get().getRootUrl();
        return root != null ? root + job.getUrl() : job.getUrl();
    }
}
