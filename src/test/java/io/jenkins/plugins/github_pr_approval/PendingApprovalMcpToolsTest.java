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
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.util.Collections;
import java.util.List;
import jenkins.branch.Branch;
import jenkins.branch.BranchSource;
import jenkins.scm.api.SCMHeadOrigin;
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy;
import org.jenkinsci.plugins.github_branch_source.BranchSCMHead;
import org.jenkinsci.plugins.github_branch_source.ForkPullRequestDiscoveryTrait;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMHead;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Drives the MCP tool methods straight, without the MCP transport, on a hand-built fork PR job. */
@WithJenkins
public class PendingApprovalMcpToolsTest {

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    private static final PullRequestSCMHead HEAD = new PullRequestSCMHead(
            "PR-7",
            "a-contributor",
            "foo-test",
            "patch-1",
            7,
            new BranchSCMHead("main"),
            new SCMHeadOrigin.Fork("a-contributor"),
            ChangeRequestCheckoutStrategy.HEAD);

    private WorkflowJob forkPullRequestJob(String projectName) throws Exception {
        WorkflowMultiBranchProject project = r.createProject(WorkflowMultiBranchProject.class, projectName);
        GitHubSCMSource source = new GitHubSCMSource("olamy", "foo-test");
        source.setApiUri("http://localhost:1/api/v3"); // must never be reached
        source.setTraits(Collections.singletonList(new ForkPullRequestDiscoveryTrait(2, new TrustExternalApproval())));
        project.setSourcesList(Collections.singletonList(new BranchSource(source)));
        WorkflowJob job = project.getProjectFactory()
                .newInstance(new Branch(source.getId(), HEAD, new hudson.scm.NullSCM(), Collections.emptyList()));
        project.addLoadedChild(job, job.getName());
        return job;
    }

    private TrustExternalApproval policyOf(WorkflowJob job) {
        WorkflowMultiBranchProject project = (WorkflowMultiBranchProject) job.getParent();
        GitHubSCMSource source = (GitHubSCMSource) project.getSCMSources().get(0);
        return (TrustExternalApproval)
                ((ForkPullRequestDiscoveryTrait) source.getTraits().get(0)).getTrust();
    }

    @Test
    public void listsOnlyPendingForkPullRequests() throws Exception {
        WorkflowJob pending = forkPullRequestJob("with-pending");
        PendingApprovalAction.ApprovalData data = new PendingApprovalAction.ApprovalData();
        data.state = PendingApprovalAction.ApprovalState.PENDING;
        data.save(pending);

        WorkflowJob approved = forkPullRequestJob("already-approved");
        PendingApprovalAction.ApprovalData ok = new PendingApprovalAction.ApprovalData();
        ok.state = PendingApprovalAction.ApprovalState.APPROVED;
        ok.approvedPullHash = "abc";
        ok.save(approved);

        List<PendingApprovalMcpTools.PendingApproval> list = new PendingApprovalMcpTools().getPendingApprovals(null);

        assertThat(
                list.stream()
                        .map(PendingApprovalMcpTools.PendingApproval::jobFullName)
                        .toList(),
                contains(pending.getFullName()));
        assertThat(list.get(0).prNumber(), is(7));
        assertThat(list.get(0).prAuthor(), is("a-contributor"));
    }

    @Test
    public void jobNameLimitsTheListToOneProject() throws Exception {
        WorkflowJob first = forkPullRequestJob("project-one");
        PendingApprovalAction.ApprovalData a = new PendingApprovalAction.ApprovalData();
        a.state = PendingApprovalAction.ApprovalState.PENDING;
        a.save(first);

        WorkflowJob second = forkPullRequestJob("project-two");
        PendingApprovalAction.ApprovalData b = new PendingApprovalAction.ApprovalData();
        b.state = PendingApprovalAction.ApprovalState.PENDING;
        b.save(second);

        PendingApprovalMcpTools tools = new PendingApprovalMcpTools();

        assertThat(
                tools.getPendingApprovals("project-one").stream()
                        .map(PendingApprovalMcpTools.PendingApproval::jobFullName)
                        .toList(),
                contains(first.getFullName()));
        // Both show up when no project is named.
        assertThat(tools.getPendingApprovals(null), hasSize(2));
        // An unknown project name yields nothing.
        assertThat(tools.getPendingApprovals("no-such-project"), is(empty()));
    }

    @Test
    public void approveUnblocksTheJob() throws Exception {
        WorkflowJob job = forkPullRequestJob("to-approve");
        PendingApprovalAction.ApprovalData data = new PendingApprovalAction.ApprovalData();
        data.state = PendingApprovalAction.ApprovalState.PENDING;
        data.save(job);

        List<PendingApprovalMcpTools.ApprovalResult> results =
                new PendingApprovalMcpTools().approvePullRequests(List.of(job.getFullName()), null);

        assertThat(results, hasSize(1));
        PendingApprovalMcpTools.ApprovalResult result = results.get(0);
        assertThat(result.approved(), is(true));
        assertThat(result.authorAutoApprovalAdded(), is(false));
        assertThat(result.error(), is(nullValue()));
        assertThat(
                PendingApprovalAction.ApprovalData.load(job).state, is(PendingApprovalAction.ApprovalState.APPROVED));
        assertThat(job.isDisabled(), is(false));
        assertThat(new PendingApprovalMcpTools().getPendingApprovals(null), is(empty()));
    }

    @Test
    public void approveCanAddTheAuthorToAutoApproval() throws Exception {
        WorkflowJob job = forkPullRequestJob("to-approve-and-trust");
        PendingApprovalAction.ApprovalData data = new PendingApprovalAction.ApprovalData();
        data.state = PendingApprovalAction.ApprovalState.PENDING;
        data.save(job);

        List<PendingApprovalMcpTools.ApprovalResult> results =
                new PendingApprovalMcpTools().approvePullRequests(List.of(job.getFullName()), true);

        assertThat(results, hasSize(1));
        assertThat(results.get(0).authorAutoApprovalAdded(), is(true));
        assertThat(policyOf(job).getAutoApprovalUsers(), contains("a-contributor"));
    }

    @Test
    public void approveReportsAJobThatIsNotAForkPullRequest() throws Exception {
        r.createFreeStyleProject("plain-job");

        List<PendingApprovalMcpTools.ApprovalResult> results =
                new PendingApprovalMcpTools().approvePullRequests(List.of("plain-job"), null);

        assertThat(results, hasSize(1));
        assertThat(results.get(0).approved(), is(false));
        assertThat(results.get(0).error(), is(notNullValue()));
    }

    @Test
    public void approveKeepsGoingWhenOneJobIsBad() throws Exception {
        WorkflowJob job = forkPullRequestJob("good-one");
        PendingApprovalAction.ApprovalData data = new PendingApprovalAction.ApprovalData();
        data.state = PendingApprovalAction.ApprovalState.PENDING;
        data.save(job);

        List<PendingApprovalMcpTools.ApprovalResult> results =
                new PendingApprovalMcpTools().approvePullRequests(List.of(job.getFullName(), "no-such-job"), null);

        assertThat(results, hasSize(2));
        // The good one is approved, no error.
        assertThat(results.get(0).jobFullName(), is(job.getFullName()));
        assertThat(results.get(0).approved(), is(true));
        assertThat(results.get(0).error(), is(nullValue()));
        // The bad one is reported, not thrown.
        assertThat(results.get(1).approved(), is(false));
        assertThat(results.get(1).error(), is(notNullValue()));
    }
}
