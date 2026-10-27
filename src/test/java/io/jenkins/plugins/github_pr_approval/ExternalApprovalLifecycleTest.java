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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import hudson.model.Action;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Item;
import hudson.model.ListView;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.model.User;
import hudson.model.View;
import hudson.scm.NullSCM;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.views.ListViewColumn;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import jenkins.branch.Branch;
import jenkins.branch.BranchSource;
import jenkins.model.Jenkins;
import jenkins.scm.api.SCMHeadOrigin;
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy;
import org.jenkinsci.plugins.github_branch_source.BranchSCMHead;
import org.jenkinsci.plugins.github_branch_source.ForkPullRequestDiscoveryTrait;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMHead;
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMRevision;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

/**
 * The lifecycle around a fork pull request job, without going near GitHub: a branch job is built by
 * hand out of the same pieces branch indexing would use.
 */
@WithJenkins
public class ExternalApprovalLifecycleTest {

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    @AfterEach
    void restoreWriteAccessCheck() {
        ExternalApprovalHelper.writeAccessCheck = DEFAULT_WRITE_ACCESS_CHECK;
    }

    private static final Predicate<ExternalApprovalInfo> DEFAULT_WRITE_ACCESS_CHECK =
            ExternalApprovalHelper.writeAccessCheck;

    private static final PullRequestSCMHead HEAD = new PullRequestSCMHead(
            "PR-1",
            "a-contributor",
            "foo-test",
            "patch-1",
            1,
            new BranchSCMHead("main"),
            new SCMHeadOrigin.Fork("a-contributor"),
            ChangeRequestCheckoutStrategy.HEAD);

    private WorkflowMultiBranchProject multiBranchProject(String name, boolean requireApprovalForNewCommits)
            throws Exception {
        TrustExternalApproval trust = new TrustExternalApproval();
        trust.setRequireApprovalForNewCommits(requireApprovalForNewCommits);
        return multiBranchProject(name, trust);
    }

    /** Same, but with the test's own trust policy, which it can still change later. */
    private WorkflowMultiBranchProject multiBranchProject(String name, TrustExternalApproval trust) throws Exception {
        WorkflowMultiBranchProject project = r.createProject(WorkflowMultiBranchProject.class, name);
        GitHubSCMSource source = new GitHubSCMSource("olamy", "foo-test");
        // Nothing here should reach GitHub; point it somewhere that refuses at once if it tries.
        source.setApiUri("http://localhost:1/api/v3");
        source.setTraits(Collections.singletonList(new ForkPullRequestDiscoveryTrait(2, trust)));
        project.setSourcesList(Collections.singletonList(new BranchSource(source)));
        return project;
    }

    /** The branch job branch indexing would have made for a fork pull request. */
    private WorkflowJob forkPullRequestJob(WorkflowMultiBranchProject project) throws Exception {
        String sourceId = project.getSCMSources().get(0).getId();
        Branch branch = new Branch(sourceId, HEAD, new NullSCM(), Collections.emptyList());
        WorkflowJob job = project.getProjectFactory().newInstance(branch);
        project.addLoadedChild(job, job.getName());
        return job;
    }

    /** Records the revision branch indexing would have written down after a scan. */
    private void lastSeen(WorkflowMultiBranchProject project, WorkflowJob job, String pullHash) throws Exception {
        project.getProjectFactory().setLastSeenRevisionHash(job, new PullRequestSCMRevision(HEAD, "base", pullHash));
    }

    @Test
    public void unknownForkPullRequestIsBlockedAndRecordsNothingYet() throws Exception {
        WorkflowJob job = forkPullRequestJob(multiBranchProject("no-record", false));

        // Branch indexing schedules the first build before it writes the revision down, so at this
        // point the head commit is unknown. The PR must still be blocked, and nothing recorded.
        assertThat(
                new PendingApprovalAction.ApprovalQueueGuard().shouldSchedule(job, Collections.emptyList()), is(false));
        assertThat(PendingApprovalAction.ApprovalData.exists(job), is(false));
    }

    @Test
    public void pendingRecordLeavesTheJobEnabledButBlocked() throws Exception {
        WorkflowJob job = forkPullRequestJob(multiBranchProject("re-enabled", false));
        PendingApprovalAction.ApprovalData data = new PendingApprovalAction.ApprovalData();
        data.state = PendingApprovalAction.ApprovalState.PENDING;
        data.save(job);

        // What an older version of this plugin left behind: a pending record and a disabled job.
        // Loading it now re-enables the job — the guard, not the disabled flag, is what holds it back.
        job.makeDisabled(true);

        Collection<? extends Action> actions = new PendingApprovalAction.ActionFactory().createFor(job);

        assertThat(actions.size(), is(1));
        assertThat(job.isDisabled(), is(false));
        assertThat(
                new PendingApprovalAction.ApprovalQueueGuard().shouldSchedule(job, Collections.emptyList()), is(false));
    }

    @Test
    public void approvedRecordLetsTheBuildThrough() throws Exception {
        WorkflowJob job = forkPullRequestJob(multiBranchProject("approved", false));
        PendingApprovalAction.ApprovalData data = new PendingApprovalAction.ApprovalData();
        data.state = PendingApprovalAction.ApprovalState.APPROVED;
        data.approvedBy = "a-maintainer";
        data.approvedPullHash = "deadbeef";
        data.save(job);
        job.makeDisabled(true);

        new PendingApprovalAction.ActionFactory().createFor(job);

        assertThat(job.isDisabled(), is(false));
        assertThat(
                new PendingApprovalAction.ApprovalQueueGuard().shouldSchedule(job, Collections.emptyList()), is(true));
    }

    @Test
    public void scanSendsAPullRequestThatMovedOnBackToPending() throws Exception {
        WorkflowMultiBranchProject project = multiBranchProject("moved-on", true);
        WorkflowJob job = forkPullRequestJob(project);
        PendingApprovalAction.ApprovalData data = new PendingApprovalAction.ApprovalData();
        data.state = PendingApprovalAction.ApprovalState.APPROVED;
        data.approvedBy = "a-maintainer";
        data.approvedPullHash = "aaaaaaa";
        data.save(job);
        job.makeDisabled(false);

        // The scan saw a new commit, and finished.
        lastSeen(project, job, "bbbbbbb");
        new PendingApprovalAction.ApprovalScanListener().taskCompleted(null, project, 0L);

        assertThat(PendingApprovalAction.ApprovalData.load(job).state, is(PendingApprovalAction.ApprovalState.PENDING));
        // The job stays enabled; the guard is what refuses the unapproved new commit.
        assertThat(job.isDisabled(), is(false));
        assertThat(
                new PendingApprovalAction.ApprovalQueueGuard().shouldSchedule(job, Collections.emptyList()), is(false));
    }

    @Test
    public void scanLeavesAnApprovalOnTheSameCommitAlone() throws Exception {
        WorkflowMultiBranchProject project = multiBranchProject("same-commit", true);
        WorkflowJob job = forkPullRequestJob(project);
        PendingApprovalAction.ApprovalData data = new PendingApprovalAction.ApprovalData();
        data.state = PendingApprovalAction.ApprovalState.APPROVED;
        data.approvedBy = "a-maintainer";
        data.approvedPullHash = "aaaaaaa";
        data.save(job);

        lastSeen(project, job, "aaaaaaa");
        new PendingApprovalAction.ApprovalScanListener().taskCompleted(null, project, 0L);

        assertThat(
                PendingApprovalAction.ApprovalData.load(job).state, is(PendingApprovalAction.ApprovalState.APPROVED));
        assertThat(job.isDisabled(), is(false));
        assertThat(
                new PendingApprovalAction.ApprovalQueueGuard().shouldSchedule(job, Collections.emptyList()), is(true));
    }

    @Test
    public void webApproveIsGatedOnTheProjectNotTheBranchJob() throws Exception {
        WorkflowMultiBranchProject project = multiBranchProject("web-approve-perm", false);
        WorkflowJob job = forkPullRequestJob(project);
        PendingApprovalAction.ApprovalData data = new PendingApprovalAction.ApprovalData();
        data.state = PendingApprovalAction.ApprovalState.PENDING;
        data.save(job);
        job.makeDisabled(true);

        // Pre-create the users, then lock the instance down the way project-based matrix does:
        // Configure on the multibranch project, nothing on the computed PR child job.
        User projectAdmin = User.getById("project-admin", true);
        User reader = User.getById("reader", true);
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        MockAuthorizationStrategy auth = new MockAuthorizationStrategy();
        auth.grant(Jenkins.READ).everywhere().to("project-admin", "reader");
        auth.grant(Item.CONFIGURE).onItems(project).to("project-admin");
        r.jenkins.setAuthorizationStrategy(auth);

        // The exact shape of the bug: Configure holds on the project but not on the branch job.
        assertThat(project.getACL().hasPermission2(projectAdmin.impersonate2(), Item.CONFIGURE), is(true));
        assertThat(job.getACL().hasPermission2(projectAdmin.impersonate2(), Item.CONFIGURE), is(false));

        PendingApprovalAction action = new PendingApprovalAction(
                job, PendingApprovalAction.ApprovalState.PENDING, 1, "a-contributor", null, false);

        // No Configure anywhere: still refused.
        try (ACLContext ignored = ACL.as2(reader.impersonate2())) {
            assertThrows(AccessDeniedException.class, () -> action.doApprove(null));
        }

        // Configure on the project is enough, even though the branch job grants this user nothing.
        try (ACLContext ignored = ACL.as2(projectAdmin.impersonate2())) {
            action.doApprove(null);
        }

        assertThat(
                PendingApprovalAction.ApprovalData.load(job).state, is(PendingApprovalAction.ApprovalState.APPROVED));
        assertThat(job.isDisabled(), is(false));
    }

    /** Writes a pending approval record for the job. */
    private void pending(WorkflowJob job) throws Exception {
        PendingApprovalAction.ApprovalData data = new PendingApprovalAction.ApprovalData();
        data.state = PendingApprovalAction.ApprovalState.PENDING;
        data.save(job);
    }

    @Test
    public void adminBuildNowIsAllowedWhilePending() throws Exception {
        WorkflowMultiBranchProject project = multiBranchProject("admin-build-now", false);
        WorkflowJob job = forkPullRequestJob(project);
        pending(job);

        // Pre-create the user, then grant Configure on the project only (project-based matrix style).
        User.getById("project-admin", true);
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        MockAuthorizationStrategy auth = new MockAuthorizationStrategy();
        auth.grant(Jenkins.READ).everywhere().to("project-admin");
        auth.grant(Item.CONFIGURE).onItems(project).to("project-admin");
        r.jenkins.setAuthorizationStrategy(auth);

        List<Action> startedByAdmin = List.of(new CauseAction(new Cause.UserIdCause("project-admin")));

        // Even under a powerless ambient identity, the admin's own Build Now goes through: the guard
        // reads the triggering user from the cause, not the current thread.
        try (ACLContext ignored = ACL.as2(Jenkins.ANONYMOUS2)) {
            assertThat(new PendingApprovalAction.ApprovalQueueGuard().shouldSchedule(job, startedByAdmin), is(true));
        }
        // The approval record is untouched: the PR still waits for everyone else.
        assertThat(PendingApprovalAction.ApprovalData.load(job).state, is(PendingApprovalAction.ApprovalState.PENDING));
    }

    @Test
    public void nonAdminOnTheIndexingThreadIsBlocked() throws Exception {
        WorkflowMultiBranchProject project = multiBranchProject("indexing-thread", false);
        WorkflowJob job = forkPullRequestJob(project);
        pending(job);

        User.getById("reader", true);
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        MockAuthorizationStrategy auth = new MockAuthorizationStrategy();
        auth.grant(Jenkins.READ).everywhere().to("reader"); // reader gets Read, never Configure
        r.jenkins.setAuthorizationStrategy(auth);

        List<Action> startedByReader = List.of(new CauseAction(new Cause.UserIdCause("reader")));

        // Branch indexing runs as the system user, which passes every permission check. If the guard
        // looked at the ambient identity it would wave this through; it must check the reader's own.
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            assertThat(new PendingApprovalAction.ApprovalQueueGuard().shouldSchedule(job, startedByReader), is(false));
        }
    }

    @Test
    public void aBuildWithNoUserBehindItIsBlocked() throws Exception {
        WorkflowMultiBranchProject project = multiBranchProject("no-user", false);
        WorkflowJob job = forkPullRequestJob(project);
        pending(job);

        // A scan or an approval build carries no UserIdCause. Even as the system user it stays blocked.
        List<Action> noUser = List.of(new CauseAction(new PendingApprovalAction.ExternalApprovalCause()));
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            assertThat(new PendingApprovalAction.ApprovalQueueGuard().shouldSchedule(job, noUser), is(false));
        }
    }

    @Test
    public void aBuildFromAnUnknownUserIsBlocked() throws Exception {
        WorkflowMultiBranchProject project = multiBranchProject("unknown-user", false);
        WorkflowJob job = forkPullRequestJob(project);
        pending(job);

        // A cause naming a user Jenkins has never heard of resolves to nothing: blocked.
        List<Action> ghost = List.of(new CauseAction(new Cause.UserIdCause("ghost")));
        assertThat(new PendingApprovalAction.ApprovalQueueGuard().shouldSchedule(job, ghost), is(false));
    }

    @Test
    public void rejectLeavesTheJobEnabledButBlocked() throws Exception {
        WorkflowMultiBranchProject project = multiBranchProject("reject", false);
        WorkflowJob job = forkPullRequestJob(project);
        PendingApprovalAction.ApprovalData data = new PendingApprovalAction.ApprovalData();
        data.state = PendingApprovalAction.ApprovalState.APPROVED;
        data.approvedBy = "a-maintainer";
        data.save(job);

        User projectAdmin = User.getById("project-admin", true);
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        MockAuthorizationStrategy auth = new MockAuthorizationStrategy();
        auth.grant(Jenkins.READ).everywhere().to("project-admin");
        auth.grant(Item.CONFIGURE).onItems(project).to("project-admin");
        r.jenkins.setAuthorizationStrategy(auth);

        PendingApprovalAction action = new PendingApprovalAction(
                job, PendingApprovalAction.ApprovalState.APPROVED, 1, "a-contributor", null, false);
        try (ACLContext ignored = ACL.as2(projectAdmin.impersonate2())) {
            action.doReject(null);
        }

        assertThat(PendingApprovalAction.ApprovalData.load(job).state, is(PendingApprovalAction.ApprovalState.PENDING));
        assertThat(job.isDisabled(), is(false));
        assertThat(
                new PendingApprovalAction.ApprovalQueueGuard().shouldSchedule(job, Collections.emptyList()), is(false));
    }

    @Test
    public void scanApprovesAPendingPullRequestOnceItsAuthorIsListed() throws Exception {
        TrustExternalApproval trust = new TrustExternalApproval();
        WorkflowMultiBranchProject project = multiBranchProject("listed-later", trust);
        WorkflowJob job = forkPullRequestJob(project);
        pending(job);
        lastSeen(project, job, "aaaaaaa");

        // The author gets added to the list after the PR went pending.
        trust.setAutoApprovalUsers("a-contributor");
        new PendingApprovalAction.ApprovalScanListener().taskCompleted(null, project, 0L);

        PendingApprovalAction.ApprovalData data = PendingApprovalAction.ApprovalData.load(job);
        assertThat(data.state, is(PendingApprovalAction.ApprovalState.APPROVED));
        assertThat(data.approvedBy, is(ExternalApprovalHelper.AUTO_APPROVAL));
        assertThat(data.approvedPullHash, is("aaaaaaa"));
        assertThat(
                new PendingApprovalAction.ApprovalQueueGuard().shouldSchedule(job, Collections.emptyList()), is(true));
    }

    @Test
    public void aPageRenderDoesNotRecheckAPendingPullRequest() throws Exception {
        TrustExternalApproval trust = new TrustExternalApproval();
        WorkflowMultiBranchProject project = multiBranchProject("render-no-recheck", trust);
        WorkflowJob job = forkPullRequestJob(project);
        pending(job);
        lastSeen(project, job, "aaaaaaa");
        trust.setAutoApprovalUsers("a-contributor");

        // Only a scan or a config save looks again; showing the page stays cheap.
        new PendingApprovalAction.ActionFactory().createFor(job);

        assertThat(PendingApprovalAction.ApprovalData.load(job).state, is(PendingApprovalAction.ApprovalState.PENDING));
    }

    @Test
    public void scanLeavesARejectedPullRequestPending() throws Exception {
        TrustExternalApproval trust = new TrustExternalApproval();
        trust.setAutoApprovalUsers("a-contributor");
        WorkflowMultiBranchProject project = multiBranchProject("rejected-stays", trust);
        WorkflowJob job = forkPullRequestJob(project);
        PendingApprovalAction.ApprovalData data = new PendingApprovalAction.ApprovalData();
        data.state = PendingApprovalAction.ApprovalState.PENDING;
        data.rejected = true;
        data.save(job);
        lastSeen(project, job, "aaaaaaa");

        // An admin rejected it by hand; a scan must not approve it again.
        new PendingApprovalAction.ApprovalScanListener().taskCompleted(null, project, 0L);

        assertThat(PendingApprovalAction.ApprovalData.load(job).state, is(PendingApprovalAction.ApprovalState.PENDING));
        assertThat(
                new PendingApprovalAction.ApprovalQueueGuard().shouldSchedule(job, Collections.emptyList()), is(false));
    }

    @Test
    public void writerCheckStaysPendingWhenGitHubCannotBeReached() throws Exception {
        TrustExternalApproval trust = new TrustExternalApproval();
        trust.setAutoApproveWriters(true);
        WorkflowMultiBranchProject project = multiBranchProject("writer-unreachable", trust);
        WorkflowJob job = forkPullRequestJob(project);
        pending(job);
        lastSeen(project, job, "aaaaaaa");

        // Nothing listens on localhost:1, so the GitHub call fails, and that must count as "no".
        new PendingApprovalAction.ApprovalScanListener().taskCompleted(null, project, 0L);

        assertThat(PendingApprovalAction.ApprovalData.load(job).state, is(PendingApprovalAction.ApprovalState.PENDING));
        assertThat(
                new PendingApprovalAction.ApprovalQueueGuard().shouldSchedule(job, Collections.emptyList()), is(false));
    }

    /** A job approved because its author has write access, on an older commit. */
    private WorkflowJob writerApprovedOnAnOlderCommit(String name, boolean autoApproveWriters) throws Exception {
        TrustExternalApproval trust = new TrustExternalApproval();
        trust.setRequireApprovalForNewCommits(true);
        trust.setAutoApproveWriters(autoApproveWriters);
        WorkflowMultiBranchProject project = multiBranchProject(name, trust);
        WorkflowJob job = forkPullRequestJob(project);
        PendingApprovalAction.ApprovalData data = new PendingApprovalAction.ApprovalData();
        data.state = PendingApprovalAction.ApprovalState.APPROVED;
        data.approvedBy = ExternalApprovalHelper.WRITE_PERMISSION;
        data.approvedPullHash = "aaaaaaa";
        data.save(job);
        // A new push the scan hasn't caught up with yet, as with a webhook.
        lastSeen(project, job, "bbbbbbb");
        return job;
    }

    @Test
    public void aWriterDoesNotHaveToAskAgainForANewCommit() throws Exception {
        WorkflowJob job = writerApprovedOnAnOlderCommit("writer-new-commit", true);
        ExternalApprovalHelper.writeAccessCheck = info -> true;

        assertThat(
                new PendingApprovalAction.ApprovalQueueGuard().shouldSchedule(job, Collections.emptyList()), is(true));
        new PendingApprovalAction.ApprovalSpender().onStarted(new WorkflowRun(job), TaskListener.NULL);
        assertThat(
                PendingApprovalAction.ApprovalData.load(job).state, is(PendingApprovalAction.ApprovalState.APPROVED));
    }

    @Test
    public void aWriterApprovalStopsCountingOnceTheOptionIsOff() throws Exception {
        WorkflowJob job = writerApprovedOnAnOlderCommit("writer-option-off", false);

        assertThat(
                new PendingApprovalAction.ApprovalQueueGuard().shouldSchedule(job, Collections.emptyList()), is(false));
        new PendingApprovalAction.ApprovalSpender().onStarted(new WorkflowRun(job), TaskListener.NULL);
        assertThat(PendingApprovalAction.ApprovalData.load(job).state, is(PendingApprovalAction.ApprovalState.PENDING));
    }

    @Test
    public void scanApprovesAPendingPullRequestFromAWriter() throws Exception {
        TrustExternalApproval trust = new TrustExternalApproval();
        trust.setAutoApproveWriters(true);
        WorkflowMultiBranchProject project = multiBranchProject("writer-recheck", trust);
        WorkflowJob job = forkPullRequestJob(project);
        pending(job);
        lastSeen(project, job, "aaaaaaa");
        ExternalApprovalHelper.writeAccessCheck = info -> true;

        // The PR-16003 case: held before the option was on, released by the next scan.
        new PendingApprovalAction.ApprovalScanListener().taskCompleted(null, project, 0L);

        PendingApprovalAction.ApprovalData data = PendingApprovalAction.ApprovalData.load(job);
        assertThat(data.state, is(PendingApprovalAction.ApprovalState.APPROVED));
        assertThat(data.approvedBy, is(ExternalApprovalHelper.WRITE_PERMISSION));
    }

    /**
     * A PR approved because its author can write to the repo, with a tiny Pipeline that leaves a mark
     * on the build if it ever runs.
     */
    private WorkflowJob writerApprovedJobWithPipeline(String name) throws Exception {
        TrustExternalApproval trust = new TrustExternalApproval();
        trust.setAutoApproveWriters(true);
        WorkflowMultiBranchProject project = multiBranchProject(name, trust);
        WorkflowJob job = forkPullRequestJob(project);
        PendingApprovalAction.ApprovalData data = new PendingApprovalAction.ApprovalData();
        data.state = PendingApprovalAction.ApprovalState.APPROVED;
        data.approvedBy = ExternalApprovalHelper.WRITE_PERMISSION;
        data.save(job);
        job.setDefinition(new CpsFlowDefinition("currentBuild.description = 'ran'", true));
        return job;
    }

    @Test
    public void aWriterWhoLostAccessHasTheirBuildStopped() throws Exception {
        WorkflowJob job = writerApprovedJobWithPipeline("writer-lost-access");
        ExternalApprovalHelper.writeAccessCheck = info -> false;

        WorkflowRun build = r.buildAndAssertStatus(Result.ABORTED, job);

        // Stopped before the Pipeline ran, and the PR waits for a person again.
        assertThat(build.getDescription(), nullValue());
        r.assertLogContains("needs a new approval", build);
        assertThat(PendingApprovalAction.ApprovalData.load(job).state, is(PendingApprovalAction.ApprovalState.PENDING));
        assertThat(
                new PendingApprovalAction.ApprovalQueueGuard().shouldSchedule(job, Collections.emptyList()), is(false));
    }

    @Test
    public void aWriterWhoStillHasAccessBuilds() throws Exception {
        WorkflowJob job = writerApprovedJobWithPipeline("writer-still-writer");
        ExternalApprovalHelper.writeAccessCheck = info -> true;

        WorkflowRun build = r.buildAndAssertSuccess(job);

        assertThat(build.getDescription(), is("ran"));
        assertThat(
                PendingApprovalAction.ApprovalData.load(job).state, is(PendingApprovalAction.ApprovalState.APPROVED));
    }

    @Test
    public void anAdminCanStillRunAOneOffBuildAfterTheWriterLostAccess() throws Exception {
        WorkflowJob job = writerApprovedJobWithPipeline("writer-lost-admin-build");
        ExternalApprovalHelper.writeAccessCheck = info -> false;
        User.getById("project-admin", true);
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        MockAuthorizationStrategy auth = new MockAuthorizationStrategy();
        auth.grant(Jenkins.READ).everywhere().to("project-admin");
        auth.grant(Item.CONFIGURE).onItems((Item) job.getParent()).to("project-admin");
        r.jenkins.setAuthorizationStrategy(auth);

        WorkflowRun build = r.assertBuildStatusSuccess(
                job.scheduleBuild2(0, new CauseAction(new Cause.UserIdCause("project-admin"))));

        // The admin's own build goes ahead, but the PR is still sent back to pending.
        assertThat(build.getDescription(), is("ran"));
        assertThat(PendingApprovalAction.ApprovalData.load(job).state, is(PendingApprovalAction.ApprovalState.PENDING));
    }

    @Test
    public void deadBranchIsNotReEnabledOnLoad() throws Exception {
        WorkflowMultiBranchProject project = multiBranchProject("dead-branch", false);
        WorkflowJob job = forkPullRequestJob(project);
        pending(job);

        // Branch-api closes a PR by marking its branch dead and disabling the job. We must not undo
        // that disable when re-enabling held pull requests.
        Branch live = project.getProjectFactory().getBranch(job);
        job = project.getProjectFactory().setBranch(job, new Branch.Dead(live));
        job.makeDisabled(true);

        new PendingApprovalAction.ActionFactory().createFor(job);

        assertThat(ExternalApprovalHelper.isDeadBranch(job), is(true));
        assertThat(job.isDisabled(), is(true));
    }

    @Test
    public void theColumnFlagsOnlyPendingForkPrs() throws Exception {
        WorkflowMultiBranchProject project = multiBranchProject("column-logic", false);
        WorkflowJob job = forkPullRequestJob(project);
        pending(job);

        PendingApprovalColumn column = new PendingApprovalColumn();
        assertThat(column.isPendingApproval(job), is(true));

        // Once approved it is no longer flagged.
        PendingApprovalAction.ApprovalData data = new PendingApprovalAction.ApprovalData();
        data.state = PendingApprovalAction.ApprovalState.APPROVED;
        data.approvedBy = "a-maintainer";
        data.save(job);
        assertThat(column.isPendingApproval(job), is(false));

        // A plain job that isn't a fork PR under the policy is never flagged.
        assertThat(column.isPendingApproval(r.createFreeStyleProject("plain-job")), is(false));
    }

    @Test
    public void theColumnIsShownRightAfterStatusInTheMultibranchViews() throws Exception {
        WorkflowMultiBranchProject project = multiBranchProject("column-view", false);
        forkPullRequestJob(project); // a child, so the category views (not the empty view) are shown

        boolean checkedAView = false;
        for (View view : project.getViews()) {
            if (view instanceof ListView listView) {
                List<ListViewColumn> columns = new ArrayList<>();
                for (ListViewColumn column : listView.getColumns()) {
                    columns.add(column);
                }
                int index = -1;
                for (int i = 0; i < columns.size(); i++) {
                    if (columns.get(i) instanceof PendingApprovalColumn) {
                        index = i;
                    }
                }
                // Second column: right after the status ball, which is always first in these views.
                assertThat("Approval column position in " + view.getClass().getSimpleName(), index, is(1));
                checkedAView = true;
            }
        }
        assertThat(checkedAView, is(true));
    }

    @Test
    public void theColumnIsHiddenFromOrdinaryViews() {
        PendingApprovalColumn.DescriptorImpl descriptor =
                r.jenkins.getDescriptorByType(PendingApprovalColumn.DescriptorImpl.class);
        // A normal dashboard list view must not get the column in its default column set.
        assertThat(new PendingApprovalColumn.BranchViewFilter().filterType(ListView.class, descriptor), is(false));
    }
}
