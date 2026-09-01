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

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.DescriptorVisibilityFilter;
import hudson.model.Job;
import hudson.views.ListViewColumn;
import hudson.views.ListViewColumnDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * A column for the multibranch <em>Branches</em> and <em>Pull Requests</em> tabs that flags fork pull
 * requests still waiting for external approval. The job is no longer disabled while it waits, so this
 * is the at-a-glance cue in the list that someone needs to look at it; the row links to the approval
 * page.
 */
public class PendingApprovalColumn extends ListViewColumn {

    @DataBoundConstructor
    public PendingApprovalColumn() {}

    /** Whether this row is a fork PR that would be refused a build right now. Drives the column cell. */
    public boolean isPendingApproval(Job<?, ?> job) {
        ExternalApprovalInfo info = ExternalApprovalHelper.getApprovalInfo(job);
        return info != null && PendingApprovalAction.isBlocked(job, info);
    }

    // Ordinal 58.5 places the column right after the status ball (StatusColumn is 59, WeatherColumn
    // 58): the default column list is ordered by descriptor ordinal, highest first.
    @Extension(ordinal = ListViewColumn.DEFAULT_COLUMNS_ORDINAL_ICON_START - 1.5)
    public static class DescriptorImpl extends ListViewColumnDescriptor {

        @Override
        public String getDisplayName() {
            return Messages.PendingApprovalColumn_displayName();
        }

        /**
         * Shown by default so it turns up in the multibranch Branches and Pull Requests tabs with no
         * setup — those views are built from the shown-by-default columns and can't be edited. A
         * {@link BranchViewFilter} keeps it out of ordinary dashboards.
         */
        @Override
        public boolean shownByDefault() {
            return true;
        }
    }

    /**
     * Confines the column to the multibranch and organization-folder branch views. Those synthetic
     * views take their columns from the shown-by-default set (there is no per-view column config to opt
     * in with), so without this the column would also appear by default in every other list view and
     * dashboard.
     */
    @Extension
    public static class BranchViewFilter extends DescriptorVisibilityFilter {

        @Override
        public boolean filterType(Class<?> contextClass, Descriptor descriptor) {
            if (descriptor instanceof DescriptorImpl) {
                return isBranchView(contextClass);
            }
            return true;
        }

        @Override
        public boolean filter(Object context, Descriptor descriptor) {
            // Only the default-column computation (filterType) is scoped; leave the column addable by
            // hand elsewhere.
            return true;
        }

        private static boolean isBranchView(Class<?> contextClass) {
            for (Class<?> c = contextClass; c != null; c = c.getSuperclass()) {
                // Internal branch-api view classes both extend BaseView; match by name so we don't take
                // a hard dependency on an internal type.
                if (c.getName().equals("jenkins.branch.BaseView")) {
                    return true;
                }
            }
            return false;
        }
    }
}
