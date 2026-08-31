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
import static org.junit.jupiter.api.Assertions.assertThrows;

import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import java.util.Collections;
import java.util.List;
import jenkins.branch.Branch;
import jenkins.branch.BranchSource;
import jenkins.model.Jenkins;
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
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

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

        PendingApprovalMcpTools.ApprovalResult result =
                new PendingApprovalMcpTools().approvePullRequest(job.getFullName(), null);

        assertThat(result.approved(), is(true));
        assertThat(result.authorAutoApprovalAdded(), is(false));
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

        PendingApprovalMcpTools.ApprovalResult result =
                new PendingApprovalMcpTools().approvePullRequest(job.getFullName(), true);

        assertThat(result.authorAutoApprovalAdded(), is(true));
        assertThat(policyOf(job).getAutoApprovalUsers(), contains("a-contributor"));
    }

    @Test
    public void approveRejectsAJobThatIsNotAForkPullRequest() throws Exception {
        r.createFreeStyleProject("plain-job");
        assertThrows(
                IllegalArgumentException.class,
                () -> new PendingApprovalMcpTools().approvePullRequest("plain-job", null));
    }

    @Test
    public void approveNeedsConfigureOnTheProjectNotTheBranchJob() throws Exception {
        WorkflowJob job = forkPullRequestJob("mcp-project-perm");
        WorkflowMultiBranchProject project = (WorkflowMultiBranchProject) job.getParent();
        PendingApprovalAction.ApprovalData data = new PendingApprovalAction.ApprovalData();
        data.state = PendingApprovalAction.ApprovalState.PENDING;
        data.save(job);

        // Configure on the multibranch project, nothing extra on the branch job — the shape
        // project-based matrix authorization produces. Read on the folder lets the tool find the job.
        User approver = User.getById("approver", true);
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        MockAuthorizationStrategy auth = new MockAuthorizationStrategy();
        auth.grant(Jenkins.READ).everywhere().to("approver");
        auth.grant(Item.READ).onFolders(project).to("approver");
        auth.grant(Item.CONFIGURE).onItems(project).to("approver");
        r.jenkins.setAuthorizationStrategy(auth);

        PendingApprovalMcpTools.ApprovalResult result;
        try (ACLContext ignored = ACL.as2(approver.impersonate2())) {
            result = new PendingApprovalMcpTools().approvePullRequest(job.getFullName(), null);
        }
        assertThat(result.approved(), is(true));
        assertThat(
                PendingApprovalAction.ApprovalData.load(job).state, is(PendingApprovalAction.ApprovalState.APPROVED));
    }

    @Test
    public void approveIsDeniedWithoutConfigure() throws Exception {
        WorkflowJob job = forkPullRequestJob("mcp-no-perm");
        WorkflowMultiBranchProject project = (WorkflowMultiBranchProject) job.getParent();
        PendingApprovalAction.ApprovalData data = new PendingApprovalAction.ApprovalData();
        data.state = PendingApprovalAction.ApprovalState.PENDING;
        data.save(job);

        // Can see the job, but has no Configure to approve it with.
        User reader = User.getById("mcp-reader", true);
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        MockAuthorizationStrategy auth = new MockAuthorizationStrategy();
        auth.grant(Jenkins.READ).everywhere().to("mcp-reader");
        auth.grant(Item.READ).onFolders(project).to("mcp-reader");
        r.jenkins.setAuthorizationStrategy(auth);

        try (ACLContext ignored = ACL.as2(reader.impersonate2())) {
            assertThrows(
                    AccessDeniedException.class,
                    () -> new PendingApprovalMcpTools().approvePullRequest(job.getFullName(), null));
        }
        assertThat(PendingApprovalAction.ApprovalData.load(job).state, is(PendingApprovalAction.ApprovalState.PENDING));
    }
}
