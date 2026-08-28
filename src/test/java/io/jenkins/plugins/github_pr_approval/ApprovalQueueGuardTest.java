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

import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class ApprovalQueueGuardTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    @Test
    public void guardIsRegistered() {
        assertThat(
                ExtensionList.lookup(hudson.model.Queue.QueueDecisionHandler.class)
                        .get(PendingApprovalAction.ApprovalQueueGuard.class),
                org.hamcrest.Matchers.notNullValue());
    }

    @Test
    public void ordinaryJobsAreNotBlocked() throws Exception {
        // A job that is not a fork PR under the external-approval policy must schedule as normal.
        FreeStyleProject job = r.createFreeStyleProject("regular-job");
        PendingApprovalAction.ApprovalQueueGuard guard = new PendingApprovalAction.ApprovalQueueGuard();
        assertThat(guard.shouldSchedule(job, Collections.emptyList()), is(true));
    }
}
