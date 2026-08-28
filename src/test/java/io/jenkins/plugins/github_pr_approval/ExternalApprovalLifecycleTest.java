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

import hudson.model.Action;
import hudson.scm.NullSCM;
import java.util.Collection;
import java.util.Collections;
import jenkins.branch.Branch;
import jenkins.branch.BranchSource;
import jenkins.scm.api.SCMHeadOrigin;
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy;
import org.jenkinsci.plugins.github_branch_source.BranchSCMHead;
import org.jenkinsci.plugins.github_branch_source.ForkPullRequestDiscoveryTrait;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMHead;
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMRevision;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * The lifecycle around a fork pull request job, without going near GitHub: a branch job is built by
 * hand out of the same pieces branch indexing would use.
 */
public class ExternalApprovalLifecycleTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

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
        WorkflowMultiBranchProject project = r.createProject(WorkflowMultiBranchProject.class, name);
        GitHubSCMSource source = new GitHubSCMSource("olamy", "foo-test");
        // Nothing here should reach GitHub; point it somewhere that refuses at once if it tries.
        source.setApiUri("http://localhost:1/api/v3");
        TrustExternalApproval trust = new TrustExternalApproval();
        trust.setRequireApprovalForNewCommits(requireApprovalForNewCommits);
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
    public void pendingRecordPutsAnEnabledJobBackToDisabled() throws Exception {
        WorkflowJob job = forkPullRequestJob(multiBranchProject("re-enabled", false));
        PendingApprovalAction.ApprovalData data = new PendingApprovalAction.ApprovalData();
        data.state = PendingApprovalAction.ApprovalState.PENDING;
        data.save(job);

        // What a restart or a re-index leaves behind: the record says pending, the job says enabled.
        job.makeDisabled(false);

        Collection<? extends Action> actions = new PendingApprovalAction.ActionFactory().createFor(job);

        assertThat(actions.size(), is(1));
        assertThat(job.isDisabled(), is(true));
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
        assertThat(job.isDisabled(), is(true));
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
}
