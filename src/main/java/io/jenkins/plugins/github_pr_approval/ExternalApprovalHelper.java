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

import com.cloudbees.plugins.credentials.common.StandardCredentials;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Job;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.branch.Branch;
import jenkins.branch.BranchProjectFactory;
import jenkins.branch.BranchSource;
import jenkins.branch.MultiBranchProject;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadObserver;
import jenkins.scm.api.SCMHeadOrigin;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.trait.SCMSourceTrait;
import org.jenkinsci.plugins.github_branch_source.AbstractGitHubNotificationStrategy;
import org.jenkinsci.plugins.github_branch_source.Connector;
import org.jenkinsci.plugins.github_branch_source.ForkPullRequestDiscoveryTrait;
import org.jenkinsci.plugins.github_branch_source.GitHubNotificationContext;
import org.jenkinsci.plugins.github_branch_source.GitHubNotificationRequest;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSourceContext;
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMHead;
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMRevision;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

/**
 * Works out whether a branch job needs external approval before it can build, and whether its pull
 * request can be approved without asking anyone.
 */
final class ExternalApprovalHelper {

    private static final Logger LOGGER = Logger.getLogger(ExternalApprovalHelper.class.getName());

    private ExternalApprovalHelper() {}

    /**
     * The approval details for a branch job, or {@code null} when it isn't a fork pull request under
     * the {@link TrustExternalApproval} policy.
     */
    @CheckForNull
    @SuppressWarnings({"rawtypes", "unchecked"})
    static ExternalApprovalInfo getApprovalInfo(Job<?, ?> job) {
        if (!(job.getParent() instanceof MultiBranchProject)) {
            return null;
        }
        MultiBranchProject mp = (MultiBranchProject) job.getParent();
        BranchProjectFactory factory = mp.getProjectFactory();
        if (!factory.isProject(job)) {
            return null;
        }
        Branch branch = factory.getBranch(job);
        SCMHead head = branch.getHead();
        if (!(head instanceof PullRequestSCMHead)) {
            return null;
        }
        PullRequestSCMHead prHead = (PullRequestSCMHead) head;
        if (prHead.getOrigin().equals(SCMHeadOrigin.DEFAULT)) {
            return null;
        }
        GitHubSCMSource source = findSourceWithExternalApproval(mp);
        if (source == null) {
            return null;
        }
        TrustExternalApproval trustPolicy = getTrustPolicy(source);
        if (trustPolicy == null) {
            return null;
        }
        String currentPullHash = getCurrentPullHash(factory, job);
        return new ExternalApprovalInfo(
                prHead.getNumber(),
                prHead.getSourceOwner(),
                currentPullHash,
                trustPolicy.isRequireApprovalForNewCommits(),
                trustPolicy.getAutoApprovalUsers(),
                trustPolicy.getAutoApprovalLabels(),
                source,
                mp);
    }

    /**
     * Whether branch-api has marked this job's branch dead — the pull request is closed or gone. We
     * leave dead branches disabled: that disable is branch-api's own doing, so we should not fight it
     * when we re-enable held pull requests.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static boolean isDeadBranch(Job<?, ?> job) {
        if (!(job.getParent() instanceof MultiBranchProject)) {
            return false;
        }
        MultiBranchProject mp = (MultiBranchProject) job.getParent();
        BranchProjectFactory factory = mp.getProjectFactory();
        if (!factory.isProject(job)) {
            return false;
        }
        return factory.getBranch(job) instanceof Branch.Dead;
    }

    /** Finds the project's {@link GitHubSCMSource} that uses the external-approval policy, if any. */
    @CheckForNull
    @SuppressWarnings("rawtypes")
    private static GitHubSCMSource findSourceWithExternalApproval(MultiBranchProject mp) {
        for (Object src : mp.getSources()) {
            if (src instanceof BranchSource) {
                SCMSource source = ((BranchSource) src).getSource();
                if (source instanceof GitHubSCMSource && getTrustPolicy((GitHubSCMSource) source) != null) {
                    return (GitHubSCMSource) source;
                }
            }
        }
        return null;
    }

    /**
     * Adds a GitHub login to the policy's auto-approval users and persists it on the multibranch
     * project, so this contributor's future pull requests are approved on sight. The match is
     * case-insensitive: a login already on the list is left as-is. Returns {@code false} when the
     * job isn't a fork PR under this policy. The caller is responsible for the permission check.
     *
     * <p>This only edits the trait; it does not re-evaluate the jobs already waiting. A branch scan
     * (or approving the PR at the same time, as the MCP tool does) is what puts them in step.
     */
    @SuppressWarnings("rawtypes")
    static boolean addAutoApprovalUser(Job<?, ?> job, String login) throws IOException {
        if (!(job.getParent() instanceof MultiBranchProject)) {
            return false;
        }
        MultiBranchProject mp = (MultiBranchProject) job.getParent();
        GitHubSCMSource source = findSourceWithExternalApproval(mp);
        if (source == null) {
            return false;
        }
        TrustExternalApproval policy = getTrustPolicy(source);
        if (policy == null) {
            return false;
        }
        List<String> current = policy.getAutoApprovalUsers();
        List<String> users = new ArrayList<>(current == null ? List.of() : current);
        for (String existing : users) {
            if (existing.equalsIgnoreCase(login)) {
                return true;
            }
        }
        users.add(login);
        policy.setAutoApprovalUsersList(users);
        mp.save();
        return true;
    }

    @CheckForNull
    private static TrustExternalApproval getTrustPolicy(GitHubSCMSource source) {
        for (SCMSourceTrait trait : source.getTraits()) {
            if (trait instanceof ForkPullRequestDiscoveryTrait) {
                Object trust = ((ForkPullRequestDiscoveryTrait) trait).getTrust();
                if (trust instanceof TrustExternalApproval) {
                    return (TrustExternalApproval) trust;
                }
            }
        }
        return null;
    }

    /**
     * Returns the pull request head as branch indexing last saw it. It has to be the last seen
     * revision and not the last built one: branch-api records the built revision only after a build
     * has been scheduled, so that one still holds the previous commit while we decide about the new
     * one.
     */
    @CheckForNull
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String getCurrentPullHash(BranchProjectFactory factory, Job<?, ?> job) {
        String pullHash = pullHashOf(factory.getLastSeenRevision(job));
        return pullHash != null ? pullHash : pullHashOf(factory.getRevision(job));
    }

    @CheckForNull
    private static String pullHashOf(@CheckForNull SCMRevision revision) {
        return revision instanceof PullRequestSCMRevision ? ((PullRequestSCMRevision) revision).getPullHash() : null;
    }

    /** Returns {@code true} when the PR author is on the auto-approval list. Just a list lookup. */
    static boolean isAutoApprovedUser(ExternalApprovalInfo info) {
        if (info.autoApprovalUsers == null || info.prAuthor == null) {
            return false;
        }
        for (String user : info.autoApprovalUsers) {
            // GitHub logins are case-insensitive.
            if (user.equalsIgnoreCase(info.prAuthor)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Decides whether a pull request can be approved without asking anyone, because its author is on
     * the user list or it carries one of the auto-approval labels. The label check costs a GitHub
     * call, so only ask when first recording the approval or when the commit has moved on.
     */
    static boolean evaluateAutoApproval(ExternalApprovalInfo info) {
        if (isAutoApprovedUser(info)) {
            return true;
        }
        if (info.autoApprovalLabels == null || info.autoApprovalLabels.isEmpty() || info.source == null) {
            return false;
        }
        GitHubSCMSource src = info.source;
        StandardCredentials credentials = Connector.lookupScanCredentials(
                info.context, src.getApiUri(), src.getCredentialsId(), src.getRepoOwner());
        GitHub github = null;
        try {
            github = Connector.connect(src.getApiUri(), credentials);
            GHRepository repo = github.getRepository(src.getRepoOwner() + "/" + src.getRepository());
            GHPullRequest pr = repo.getPullRequest(info.prNumber);
            for (GHLabel label : pr.getLabels()) {
                if (info.autoApprovalLabels.contains(label.getName())) {
                    return true;
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to check auto-approval labels for PR #" + info.prNumber, e);
        } finally {
            if (github != null) {
                Connector.release(github);
            }
        }
        return false;
    }

    /**
     * Posts a yellow "pending" commit status — "Awaiting maintainer approval" — to the pull request
     * while it is held, so on GitHub the PR clearly shows it is waiting rather than looking unbuilt
     * or (worse) green from an earlier run. We reuse the source's own notification strategy so the
     * status lands on the same context the real build uses; that way, once the PR is approved and
     * builds, the normal pending/success status cleanly overwrites this one (no orphaned yellow).
     *
     * <p>Best effort. Returns {@code true} when there is nothing left to do (the status was posted,
     * or notifications are switched off) and {@code false} only on a transient GitHub failure, so the
     * caller can retry it on the next scan rather than giving up.
     */
    static boolean notifyAwaitingApproval(Job<?, ?> job, ExternalApprovalInfo info) {
        GitHubSCMSource src = info.source;
        if (src == null || info.currentPullHash == null) {
            return true;
        }
        SCMHead head = headOf(job);
        if (!(head instanceof PullRequestSCMHead)) {
            return true;
        }
        // Honour the source's "Disable notifications" trait, just like the build status notifier does.
        GitHubSCMSourceContext sourceContext =
                new GitHubSCMSourceContext(null, SCMHeadObserver.none()).withTraits(src.getTraits());
        if (sourceContext.notificationsDisabled()) {
            return true;
        }
        StandardCredentials credentials = Connector.lookupScanCredentials(
                info.context, src.getApiUri(), src.getCredentialsId(), src.getRepoOwner());
        GitHub github = null;
        try {
            github = Connector.connect(src.getApiUri(), credentials);
            GHRepository repo = github.getRepository(src.getRepoOwner() + "/" + src.getRepository());
            for (AbstractGitHubNotificationStrategy strategy : sourceContext.notificationStrategies()) {
                // Take the context and target URL the strategy would use, but force our own state and
                // message so the status reads as "waiting for approval" rather than "queued/building".
                GitHubNotificationContext notificationContext = GitHubNotificationContext.build(job, null, src, head);
                for (GitHubNotificationRequest request : strategy.notifications(notificationContext, null)) {
                    repo.createCommitStatus(
                            info.currentPullHash,
                            GHCommitState.PENDING,
                            request.getUrl(),
                            "Awaiting maintainer approval",
                            request.getContext());
                }
            }
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to set the awaiting-approval status for PR #" + info.prNumber, e);
            return false;
        } finally {
            if (github != null) {
                Connector.release(github);
            }
        }
    }

    @CheckForNull
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static SCMHead headOf(Job<?, ?> job) {
        if (!(job.getParent() instanceof MultiBranchProject)) {
            return null;
        }
        BranchProjectFactory factory = ((MultiBranchProject) job.getParent()).getProjectFactory();
        if (!factory.isProject(job)) {
            return null;
        }
        return factory.getBranch(job).getHead();
    }
}
