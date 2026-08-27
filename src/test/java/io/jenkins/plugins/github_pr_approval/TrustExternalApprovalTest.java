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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;

import hudson.util.XStream2;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import jenkins.branch.BranchSource;
import jenkins.scm.api.SCMHeadObserver;
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy;
import jenkins.scm.api.trait.SCMHeadFilter;
import jenkins.scm.api.trait.SCMHeadPrefilter;
import jenkins.scm.api.trait.SCMSourceTrait;
import org.hamcrest.Matchers;
import org.jenkinsci.plugins.github_branch_source.ForkPullRequestDiscoveryTrait;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSourceContext;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class TrustExternalApprovalTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    @Test
    public void appliedToContext() {
        GitHubSCMSourceContext ctx = new GitHubSCMSourceContext(null, SCMHeadObserver.none());
        assumeThat(ctx.wantBranches(), is(false));
        assumeThat(ctx.wantPRs(), is(false));
        assumeThat(ctx.prefilters(), is(Collections.<SCMHeadPrefilter>emptyList()));
        assumeThat(ctx.filters(), is(Collections.<SCMHeadFilter>emptyList()));
        assumeThat(ctx.authorities(), not(hasItem(instanceOf(TrustExternalApproval.class))));
        ForkPullRequestDiscoveryTrait instance = new ForkPullRequestDiscoveryTrait(
                EnumSet.allOf(ChangeRequestCheckoutStrategy.class), new TrustExternalApproval());
        instance.decorateContext(ctx);
        assertThat(ctx.wantBranches(), is(false));
        assertThat(ctx.wantPRs(), is(true));
        assertThat(ctx.prefilters(), is(Collections.<SCMHeadPrefilter>emptyList()));
        assertThat(ctx.filters(), is(Collections.<SCMHeadFilter>emptyList()));
        assertThat(ctx.forkPRStrategies(), Matchers.is(EnumSet.allOf(ChangeRequestCheckoutStrategy.class)));
        assertThat(ctx.authorities(), hasItem(instanceOf(TrustExternalApproval.class)));
    }

    @Test
    public void requireApprovalForNewCommits() {
        TrustExternalApproval trust = new TrustExternalApproval();
        assertThat(trust.isRequireApprovalForNewCommits(), is(false));
        trust.setRequireApprovalForNewCommits(true);
        assertThat(trust.isRequireApprovalForNewCommits(), is(true));
    }

    @Test
    public void autoApprovalUsersList() {
        TrustExternalApproval trust = new TrustExternalApproval();
        assertThat(trust.getAutoApprovalUsers(), nullValue());

        trust.setAutoApprovalUsersList(Arrays.asList("user1", "user2"));
        assertThat(trust.getAutoApprovalUsers(), is(Arrays.asList("user1", "user2")));
        assertThat(trust.getAutoApprovalUsersString(), is("user1, user2"));

        trust.setAutoApprovalUsersList(Collections.emptyList());
        assertThat(trust.getAutoApprovalUsers(), nullValue());

        trust.setAutoApprovalUsersList(null);
        assertThat(trust.getAutoApprovalUsers(), nullValue());
    }

    @Test
    public void autoApprovalUsersString() {
        TrustExternalApproval trust = new TrustExternalApproval();

        trust.setAutoApprovalUsers("user1, user2, user3");
        assertThat(trust.getAutoApprovalUsers(), is(Arrays.asList("user1", "user2", "user3")));

        trust.setAutoApprovalUsers("  user1 ,  user2  ");
        assertThat(trust.getAutoApprovalUsers(), is(Arrays.asList("user1", "user2")));

        trust.setAutoApprovalUsers("");
        assertThat(trust.getAutoApprovalUsers(), nullValue());

        trust.setAutoApprovalUsers((String) null);
        assertThat(trust.getAutoApprovalUsers(), nullValue());
    }

    @Test
    public void autoApprovalLabels() {
        TrustExternalApproval trust = new TrustExternalApproval();
        assertThat(trust.getAutoApprovalLabels(), nullValue());

        trust.setAutoApprovalLabelsList(Arrays.asList("safe-to-build", "approved"));
        assertThat(trust.getAutoApprovalLabels(), is(Arrays.asList("safe-to-build", "approved")));
        assertThat(trust.getAutoApprovalLabelsString(), is("safe-to-build, approved"));

        trust.setAutoApprovalLabelsList(Collections.emptyList());
        assertThat(trust.getAutoApprovalLabels(), nullValue());

        trust.setAutoApprovalLabels("label1, label2");
        assertThat(trust.getAutoApprovalLabels(), is(Arrays.asList("label1", "label2")));

        trust.setAutoApprovalLabels((String) null);
        assertThat(trust.getAutoApprovalLabels(), nullValue());
    }

    @Test
    public void xstream() {
        TrustExternalApproval trust = new TrustExternalApproval();
        trust.setRequireApprovalForNewCommits(true);
        trust.setAutoApprovalLabelsList(Arrays.asList("safe-to-build", "ci-approved"));
        trust.setAutoApprovalUsersList(Arrays.asList("octocat", "dependabot"));
        String xml = new XStream2().toXML(new ForkPullRequestDiscoveryTrait(3, trust));
        assertThat(xml, containsString("TrustExternalApproval"));
        assertThat(xml, containsString("requireApprovalForNewCommits"));
        assertThat(xml, containsString("autoApprovalLabels"));
        assertThat(xml, containsString("safe-to-build"));
        assertThat(xml, containsString("ci-approved"));
        assertThat(xml, containsString("autoApprovalUsers"));
        assertThat(xml, containsString("octocat"));
        assertThat(xml, containsString("dependabot"));
    }

    @Test
    public void configRoundtripWithRawUrl() throws Exception {
        WorkflowMultiBranchProject p = r.createProject(WorkflowMultiBranchProject.class);
        GitHubSCMSource s = new GitHubSCMSource("", "", "https://github.com/nobody/nowhere", true);
        p.setSourcesList(Collections.singletonList(new BranchSource(s)));
        s.setTraits(Collections.singletonList(new ForkPullRequestDiscoveryTrait(0, new TrustExternalApproval())));
        r.configRoundtrip(p);
        List<SCMSourceTrait> traits =
                ((GitHubSCMSource) p.getSourcesList().get(0).getSource()).getTraits();
        assertEquals(1, traits.size());
        assertEquals(
                TrustExternalApproval.class,
                ((ForkPullRequestDiscoveryTrait) traits.get(0)).getTrust().getClass());
        r.waitUntilNoActivity();
    }
}
