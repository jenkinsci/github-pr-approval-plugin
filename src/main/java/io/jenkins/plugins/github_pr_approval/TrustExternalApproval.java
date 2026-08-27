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
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Item;
import hudson.util.FormValidation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jenkins.model.Jenkins;
import jenkins.scm.api.SCMHeadOrigin;
import jenkins.scm.api.trait.SCMHeadAuthorityDescriptor;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.github_branch_source.ForkPullRequestDiscoveryTrait;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSourceRequest;
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMHead;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

    /**
     * An {@link SCMHeadAuthority} that holds fork pull requests back until someone approves them.
     * The job starts out disabled and only builds once an administrator has approved it. Pull
     * requests from a trusted login, or carrying a trusted label, are approved for you.
     */
    public class TrustExternalApproval extends ForkPullRequestDiscoveryTrait.GitHubForkTrustPolicy {
        private boolean requireApprovalForNewCommits;

        @CheckForNull
        private List<String> autoApprovalLabels;

        @CheckForNull
        private List<String> autoApprovalUsers;

        /** Constructor. */
        @DataBoundConstructor
        public TrustExternalApproval() {}

        /** Whether every new commit on the pull request has to be approved again. */
        public boolean isRequireApprovalForNewCommits() {
            return requireApprovalForNewCommits;
        }

        @DataBoundSetter
        public void setRequireApprovalForNewCommits(boolean requireApprovalForNewCommits) {
            this.requireApprovalForNewCommits = requireApprovalForNewCommits;
        }

        /** The labels that approve a pull request on sight, or {@code null} if none are set. */
        @CheckForNull
        public List<String> getAutoApprovalLabels() {
            return autoApprovalLabels;
        }

        /** The same labels as one comma-separated string, which is how the config form wants them. */
        @CheckForNull
        public String getAutoApprovalLabelsString() {
            return autoApprovalLabels == null ? null : String.join(", ", autoApprovalLabels);
        }

        /** Reads the labels back from the config form, where they arrive comma-separated. */
        @DataBoundSetter
        public void setAutoApprovalLabels(@CheckForNull String autoApprovalLabels) {
            this.autoApprovalLabels = parseCommaSeparated(autoApprovalLabels);
        }

        /** Sets the same labels from a list, for callers that already have one. */
        public void setAutoApprovalLabelsList(@CheckForNull List<String> autoApprovalLabels) {
            if (autoApprovalLabels == null || autoApprovalLabels.isEmpty()) {
                this.autoApprovalLabels = null;
            } else {
                this.autoApprovalLabels = Collections.unmodifiableList(new ArrayList<>(autoApprovalLabels));
            }
        }

        /** The GitHub logins whose pull requests are approved on sight, or {@code null} if none are set. */
        @CheckForNull
        public List<String> getAutoApprovalUsers() {
            return autoApprovalUsers;
        }

        /** The same logins as one comma-separated string, which is how the config form wants them. */
        @CheckForNull
        public String getAutoApprovalUsersString() {
            return autoApprovalUsers == null ? null : String.join(", ", autoApprovalUsers);
        }

        /** Reads the logins back from the config form, where they arrive comma-separated. */
        @DataBoundSetter
        public void setAutoApprovalUsers(@CheckForNull String autoApprovalUsers) {
            this.autoApprovalUsers = parseCommaSeparated(autoApprovalUsers);
        }

        /** Sets the same logins from a list, for callers that already have one. */
        public void setAutoApprovalUsersList(@CheckForNull List<String> autoApprovalUsers) {
            if (autoApprovalUsers == null || autoApprovalUsers.isEmpty()) {
                this.autoApprovalUsers = null;
            } else {
                this.autoApprovalUsers = Collections.unmodifiableList(new ArrayList<>(autoApprovalUsers));
            }
        }

        @CheckForNull
        private static List<String> parseCommaSeparated(@CheckForNull String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            List<String> result = new ArrayList<>();
            for (String entry : value.split(",")) {
                String trimmed = entry.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
            return result.isEmpty() ? null : Collections.unmodifiableList(result);
        }

        /** {@inheritDoc} */
        @Override
        protected boolean checkTrusted(@NonNull GitHubSCMSourceRequest request, @NonNull PullRequestSCMHead head)
                throws IOException, InterruptedException {
            if (autoApprovalUsers != null) {
                for (String user : autoApprovalUsers) {
                    // GitHub logins are case-insensitive.
                    if (user.equalsIgnoreCase(head.getSourceOwner())) {
                        return true;
                    }
                }
            }
            if (autoApprovalLabels != null && !autoApprovalLabels.isEmpty()) {
                for (GHPullRequest pr : request.getPullRequests()) {
                    if (pr.getNumber() != head.getNumber()) {
                        continue;
                    }
                    for (GHLabel label : pr.getLabels()) {
                        if (autoApprovalLabels.contains(label.getName())) {
                            return true;
                        }
                    }
                    break;
                }
            }
            return false;
        }

        /** Our descriptor. */
        @Symbol("gitHubTrustExternalApproval")
        @Extension
        public static class DescriptorImpl extends SCMHeadAuthorityDescriptor {

            /** {@inheritDoc} */
            @Override
            public String getDisplayName() {
                return Messages.TrustExternalApproval_displayName();
            }

            /** {@inheritDoc} */
            @Override
            public boolean isApplicableToOrigin(@NonNull Class<? extends SCMHeadOrigin> originClass) {
                return SCMHeadOrigin.Fork.class.isAssignableFrom(originClass);
            }

            @Restricted(NoExternalUse.class)
            @SuppressWarnings("unused") // stapler
            @POST
            public FormValidation doCheckAutoApprovalUsers(
                    @CheckForNull @AncestorInPath Item context, @QueryParameter String value) {
                // Only someone who can configure the job, or an admin when there is no job in the path.
                if (context == null) {
                    Jenkins.get().checkPermission(Jenkins.ADMINISTER);
                } else {
                    context.checkPermission(Item.CONFIGURE);
                }
                if (value == null || value.isBlank()) {
                    return FormValidation.ok();
                }
                for (String entry : value.split(",")) {
                    String trimmed = entry.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    if (trimmed.contains(" ")) {
                        return FormValidation.warning("GitHub logins should not contain spaces: '" + trimmed + "'");
                    }
                }
                return FormValidation.ok();
            }
        }
    }
