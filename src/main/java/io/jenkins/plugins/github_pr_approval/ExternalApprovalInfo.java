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
import java.util.List;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;

/** A snapshot of a fork pull request, with everything needed to decide its approval. */
class ExternalApprovalInfo {
    final int prNumber;
    final String prAuthor;
    final String currentPullHash;
    final boolean requireApprovalForNewCommits;

    @CheckForNull
    final List<String> autoApprovalUsers;

    @CheckForNull
    final List<String> autoApprovalLabels;

    @CheckForNull
    final GitHubSCMSource source;

    @CheckForNull
    final Item context;

    ExternalApprovalInfo(
            int prNumber,
            String prAuthor,
            String currentPullHash,
            boolean requireApprovalForNewCommits,
            @CheckForNull List<String> autoApprovalUsers,
            @CheckForNull List<String> autoApprovalLabels,
            @CheckForNull GitHubSCMSource source,
            @CheckForNull Item context) {
        this.prNumber = prNumber;
        this.prAuthor = prAuthor;
        this.currentPullHash = currentPullHash;
        this.requireApprovalForNewCommits = requireApprovalForNewCommits;
        this.autoApprovalUsers = autoApprovalUsers;
        this.autoApprovalLabels = autoApprovalLabels;
        this.source = source;
        this.context = context;
    }
}
