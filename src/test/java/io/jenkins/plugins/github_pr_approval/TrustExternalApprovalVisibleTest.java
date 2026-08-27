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

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertThat;

import java.util.List;
import jenkins.scm.api.trait.SCMHeadAuthorityDescriptor;
import org.jenkinsci.plugins.github_branch_source.ForkPullRequestDiscoveryTrait;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * The whole premise of this plugin: our trust policy, defined here, must show up in the fork-PR
 * Trust dropdown that github-branch-source renders — with no change to that plugin. This is a
 * regression guard for that contract.
 */
public class TrustExternalApprovalVisibleTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    @Test
    public void offeredInForkTrustDropdown() {
        ForkPullRequestDiscoveryTrait.DescriptorImpl descriptor =
                r.jenkins.getDescriptorByType(ForkPullRequestDiscoveryTrait.DescriptorImpl.class);
        List<SCMHeadAuthorityDescriptor> trustDescriptors = descriptor.getTrustDescriptors();
        assertThat(trustDescriptors, hasItem(instanceOf(TrustExternalApproval.DescriptorImpl.class)));
    }
}
