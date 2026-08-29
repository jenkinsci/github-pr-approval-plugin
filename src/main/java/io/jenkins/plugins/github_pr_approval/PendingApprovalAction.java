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

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.XmlFile;
import hudson.model.Action;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.ItemListener;
import hudson.model.listeners.RunListener;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.branch.MultiBranchProject;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import jenkins.model.TransientActionFactory;
import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

/**
 * Shown on a branch job while its fork pull request waits for external approval, with the buttons
 * and endpoints an administrator uses to approve or take back that approval.
 *
 * <p>What actually holds the pull request back is {@link ApprovalQueueGuard}. The job is disabled
 * too, but only so the state is visible: the guard is what refuses the build.
 */
public class PendingApprovalAction implements Action {

    private static final Logger LOGGER = Logger.getLogger(PendingApprovalAction.class.getName());

    /** Marker stored as the approver when a PR was approved automatically. */
    private static final String AUTO_APPROVAL = "auto-approval";

    private final transient Job<?, ?> owner;
    private final ApprovalState state;
    private final int prNumber;
    private final String prAuthor;
    private final String currentPullHash;
    private final boolean requireApprovalForNewCommits;

    PendingApprovalAction(
            Job<?, ?> owner,
            ApprovalState state,
            int prNumber,
            String prAuthor,
            String currentPullHash,
            boolean requireApprovalForNewCommits) {
        this.owner = owner;
        this.state = state;
        this.prNumber = prNumber;
        this.prAuthor = prAuthor;
        this.currentPullHash = currentPullHash;
        this.requireApprovalForNewCommits = requireApprovalForNewCommits;
    }

    @Override
    public String getIconFileName() {
        if (state == ApprovalState.PENDING) {
            return "symbol-warning plugin-ionicons-api";
        }
        return null;
    }

    @Override
    public String getDisplayName() {
        if (state == ApprovalState.PENDING) {
            return Messages.PendingApprovalAction_displayName();
        }
        return Messages.PendingApprovalAction_approved();
    }

    @Override
    public String getUrlName() {
        return "pendingApproval";
    }

    public ApprovalState getState() {
        return state;
    }

    public int getPrNumber() {
        return prNumber;
    }

    public String getPrAuthor() {
        return prAuthor;
    }

    public String getCurrentPullHash() {
        return currentPullHash;
    }

    public boolean isRequireApprovalForNewCommits() {
        return requireApprovalForNewCommits;
    }

    public Job<?, ?> getOwner() {
        return owner;
    }

    /** Approves the pull request: enables the job and starts a build. */
    @POST
    public HttpResponse doApprove(StaplerRequest2 req) {
        owner.checkPermission(Item.CONFIGURE);
        approve(owner, currentPullHash, Jenkins.get().getAuthentication2().getName());
        return new HttpRedirect("..");
    }

    /**
     * Records the approval, enables the job and starts a build. Shared by the approval page and the
     * MCP tool, so it returns whether the record was actually written — a build that then fails to
     * schedule is only logged, not reported as a failure. The caller is responsible for the
     * permission check.
     */
    static boolean approve(Job<?, ?> job, @Nullable String pullHash, String approvedBy) {
        try {
            ApprovalData data = ApprovalData.load(job);
            data.state = ApprovalState.APPROVED;
            data.approvedBy = approvedBy;
            data.approvedAt = System.currentTimeMillis();
            data.approvedPullHash = pullHash;
            data.save(job);
            setDisabled(job, false);
            if (ParameterizedJobMixIn.scheduleBuild2(job, 0, new CauseAction(new ExternalApprovalCause())) == null) {
                LOGGER.log(Level.WARNING, "Failed to schedule build for {0}", job.getFullName());
            }
            LOGGER.log(Level.INFO, "{0} approved by {1}", new Object[] {job.getFullName(), approvedBy});
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to approve " + job.getFullName(), e);
            return false;
        }
    }

    /** Takes the approval back and disables the job again. */
    @POST
    public HttpResponse doReject(StaplerRequest2 req) {
        owner.checkPermission(Item.CONFIGURE);
        try {
            ApprovalData data = ApprovalData.load(owner);
            data.reset();
            data.save(owner);
            setDisabled(owner, true);
            LOGGER.log(Level.INFO, "PR #{0} in {1} rejected by {2}", new Object[] {
                prNumber,
                owner.getFullName(),
                Jenkins.get().getAuthentication2().getName()
            });
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to reject PR #" + prNumber, e);
        }
        return new HttpRedirect("..");
    }

    /** Works out where a branch job stands and puts the job in step with it. */
    private static void refresh(Job<?, ?> job) {
        ExternalApprovalInfo info = ExternalApprovalHelper.getApprovalInfo(job);
        if (info != null) {
            resolveApprovalData(job, info);
        }
    }

    /** Same, for every branch of a multibranch project. */
    @SuppressWarnings("rawtypes")
    private static void refreshBranches(MultiBranchProject<?, ?> project) {
        for (Item child : project.getItems()) {
            if (child instanceof Job<?, ?> job) {
                refresh(job);
            }
        }
    }

    /** Mirrors the approval onto the job. Anything short of an approval leaves it disabled. */
    private static void applyApprovalState(Job<?, ?> job, ApprovalState state) {
        setDisabled(job, state != ApprovalState.APPROVED);
    }

    private static void setDisabled(Job<?, ?> job, boolean disabled) {
        if (!(job instanceof ParameterizedJobMixIn.ParameterizedJob<?, ?> project)) {
            LOGGER.log(Level.WARNING, "Cannot change the disabled state of {0}", job.getFullName());
            return;
        }
        if (project.isDisabled() == disabled) {
            return;
        }
        try {
            // Disabling also cancels anything this job already has queued.
            project.makeDisabled(disabled);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Cannot change the disabled state of " + job.getFullName(), e);
        }
    }

    /** Approval state of an external pull request. */
    public enum ApprovalState {
        PENDING,
        APPROVED
    }

    /** Marks a build as having been triggered by an external approval. */
    public static class ExternalApprovalCause extends Cause {
        @Override
        public String getShortDescription() {
            return "External approval granted";
        }
    }

    /** The approval state, saved alongside the job in its directory. */
    static class ApprovalData implements Serializable {
        private static final long serialVersionUID = 1L;

        ApprovalState state = ApprovalState.PENDING;

        @Nullable
        String approvedBy;

        long approvedAt;

        @Nullable
        String approvedPullHash;

        /** The commit we last told GitHub is awaiting approval, so a re-scan doesn't repost it. */
        @Nullable
        String notifiedPullHash;

        /** Drops any approval, sending the pull request back to pending. */
        void reset() {
            state = ApprovalState.PENDING;
            approvedBy = null;
            approvedAt = 0;
            approvedPullHash = null;
            notifiedPullHash = null;
        }

        static XmlFile getConfigFile(Job<?, ?> job) {
            return new XmlFile(new File(job.getRootDir(), "pending-approval.xml"));
        }

        static ApprovalData load(Job<?, ?> job) {
            XmlFile file = getConfigFile(job);
            if (file.exists()) {
                try {
                    return (ApprovalData) file.read();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to load approval data for " + job.getFullName(), e);
                }
            }
            return new ApprovalData();
        }

        void save(Job<?, ?> job) throws IOException {
            getConfigFile(job).write(this);
        }

        static boolean exists(Job<?, ?> job) {
            return getConfigFile(job).exists();
        }

        static void delete(Job<?, ?> job) {
            XmlFile file = getConfigFile(job);
            if (file.exists()) {
                if (!file.getFile().delete()) {
                    LOGGER.log(Level.WARNING, "Failed to delete approval data for {0}", job.getFullName());
                }
            }
        }
    }

    /** Attaches a {@link PendingApprovalAction} to any branch job that needs external approval. */
    @Extension
    public static class ActionFactory extends TransientActionFactory<Job> {

        @Override
        public Class<Job> type() {
            return Job.class;
        }

        @NonNull
        @Override
        public Class<? extends Action> actionType() {
            return PendingApprovalAction.class;
        }

        @NonNull
        @Override
        public Collection<? extends Action> createFor(@NonNull Job target) {
            ExternalApprovalInfo info = ExternalApprovalHelper.getApprovalInfo(target);
            if (info == null) {
                return Collections.emptyList();
            }
            ApprovalData data = resolveApprovalData(target, info);
            return Collections.singletonList(new PendingApprovalAction(
                    target,
                    data.state,
                    info.prNumber,
                    info.prAuthor,
                    info.currentPullHash,
                    info.requireApprovalForNewCommits));
        }
    }

    /**
     * Puts a newly discovered fork pull request on hold, and catches the jobs that were already
     * there when the trust policy got switched on.
     */
    @Extension
    public static class ApprovalItemListener extends ItemListener {

        @Override
        public void onCreated(Item item) {
            if (item instanceof Job<?, ?> job) {
                refresh(job);
            }
        }

        /**
         * Nothing carries the disabled flag across a restart on its own — branch indexing rewrites
         * the branch job's config as it goes — so once everything is loaded, put every held pull
         * request back to what its approval record says. Only reads records that already exist, so
         * this never calls GitHub while Jenkins is still starting.
         */
        @Override
        public void onLoaded() {
            for (MultiBranchProject<?, ?> project : Jenkins.get().getAllItems(MultiBranchProject.class)) {
                for (Item child : project.getItems()) {
                    if (child instanceof Job<?, ?> job && ApprovalData.exists(job)) {
                        if (ExternalApprovalHelper.getApprovalInfo(job) != null) {
                            applyApprovalState(job, ApprovalData.load(job).state);
                        }
                    }
                }
            }
        }

        @Override
        public void onUpdated(Item item) {
            if (item instanceof MultiBranchProject<?, ?> project) {
                refreshBranches(project);
            }
        }
    }

    /**
     * Settles every pull request once a branch scan has finished.
     *
     * <p>Nothing else tells us a scan happened: no item listener fires for a re-index, and branch
     * indexing writes down the revision it has just seen only <em>after</em> it has scheduled the
     * build. So a scan is the one moment we most need to look again, and the only safe place to
     * look is once it is over. A pull request that has moved past the commit it was approved for
     * goes back to pending here — and because disabling a job also cancels whatever it has queued,
     * the build the scan just scheduled is cancelled with it, as long as it has not started yet.
     *
     * <p>Branch indexing runs as a queue task on the multibranch project itself, which is why this
     * hangs off {@link ExecutorListener} rather than anything branch-related.
     */
    @Extension
    public static class ApprovalScanListener implements ExecutorListener {

        @Override
        public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
            afterScan(task);
        }

        @Override
        public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
            // A scan that failed part way through can still have discovered pull requests.
            afterScan(task);
        }

        private static void afterScan(Queue.Task task) {
            if (task instanceof MultiBranchProject<?, ?> project) {
                refreshBranches(project);
            }
        }
    }

    /**
     * Spends the approval once the build it was granted for has started: the job goes back to
     * disabled, so the next commit needs a fresh approval. Only does anything when the trust policy
     * asks for approval on new commits.
     */
    @Extension
    public static class ApprovalSpender extends RunListener<Run<?, ?>> {

        @Override
        public void onStarted(Run<?, ?> run, TaskListener listener) {
            Job<?, ?> job = run.getParent();
            ExternalApprovalInfo info = ExternalApprovalHelper.getApprovalInfo(job);
            if (info == null || !info.requireApprovalForNewCommits) {
                return;
            }
            if (ExternalApprovalHelper.isAutoApprovedUser(info)) {
                // Authors on the auto-approval list never have to ask again.
                return;
            }
            ApprovalData data = ApprovalData.load(job);
            if (data.state != ApprovalState.APPROVED) {
                return;
            }
            data.reset();
            try {
                data.save(job);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to reset the approval of " + job.getFullName(), e);
                return;
            }
            setDisabled(job, true);
            listener.getLogger()
                    .println("Approval spent: the next build of PR #" + info.prNumber + " needs a new approval.");
        }
    }

    /**
     * The gate that actually stops an unapproved fork pull request from building.
     *
     * <p>Disabling the job (see {@link ApprovalItemListener}) is not enough on its own. When branch
     * indexing discovers a new fork PR it schedules that PR's first build in the same pass, and that
     * build can win the race against the job being disabled — which is how an unapproved PR slipped
     * through and built. A manual "Build Now" or a re-trigger would get past a disabled job too.
     * Jenkins asks every {@link Queue.QueueDecisionHandler} before it queues anything, so refusing
     * here blocks the build no matter what triggered it. The disabled flag is then just what the
     * user sees; this is what enforces it.
     *
     * <p>This runs while the build queue is locked, so it stays deliberately cheap: a few in-memory
     * checks and one small file read — never a GitHub call and never a write.
     */
    @Extension
    public static class ApprovalQueueGuard extends Queue.QueueDecisionHandler {

        @Override
        public boolean shouldSchedule(Queue.Task task, List<Action> actions) {
            if (!(task instanceof Job<?, ?> job)) {
                return true;
            }
            ExternalApprovalInfo info = ExternalApprovalHelper.getApprovalInfo(job);
            if (info == null) {
                // Not a fork PR under the external-approval policy: nothing to guard, let it build.
                return true;
            }
            if (isBlocked(job, info)) {
                LOGGER.log(Level.INFO, "Blocked a build of PR #{0} in {1}: it is not approved.", new Object[] {
                    info.prNumber, job.getFullName()
                });
                return false;
            }
            return true;
        }
    }

    /**
     * Whether this fork PR would be refused a build right now: still pending, or approved but a newer
     * commit has arrived while "require approval for new commits" is on — unless the author is on the
     * auto-approval list, who never has to ask again.
     *
     * <p>Only in-memory work plus the one {@code pending-approval.xml} read, so both the queue guard
     * and the MCP list tool can call it freely.
     */
    static boolean isBlocked(Job<?, ?> job, ExternalApprovalInfo info) {
        ApprovalData data = ApprovalData.load(job);
        if (data.state != ApprovalState.APPROVED) {
            return true;
        }
        return info.requireApprovalForNewCommits
                && data.approvedPullHash != null
                && !data.approvedPullHash.equals(info.currentPullHash)
                && !ExternalApprovalHelper.isAutoApprovedUser(info);
    }

    /**
     * Loads the approval record, writing it the first time we see a pull request and looking at it
     * again once the approved commit has moved on. This is the only writer of the approval state,
     * and it can cost a GitHub call to read labels, so it does nothing at all in between. Whatever
     * it changes is saved and mirrored onto the job.
     */
    private static ApprovalData resolveApprovalData(Job<?, ?> job, ExternalApprovalInfo info) {
        ApprovalData data = ApprovalData.load(job);
        if (!ApprovalData.exists(job) && info.currentPullHash == null) {
            // Branch indexing schedules a new pull request's first build before it writes down the
            // revision, so the first time we are asked about a PR its head commit is often still
            // unknown. Hold it as pending without recording anything: an approval pinned to a null
            // commit would never expire, and we could not tell GitHub which commit is waiting.
            // The next call, once the revision is on disk, decides for real.
            applyApprovalState(job, data.state);
            return data;
        }
        boolean changed = false;
        if (!ApprovalData.exists(job)) {
            // First time we see this PR: approve it straight away if it matches the users or labels.
            if (ExternalApprovalHelper.evaluateAutoApproval(info)) {
                data.state = ApprovalState.APPROVED;
                data.approvedBy = AUTO_APPROVAL;
                data.approvedPullHash = info.currentPullHash;
            } else {
                data.state = ApprovalState.PENDING;
            }
            changed = true;
        } else if (info.requireApprovalForNewCommits
                && data.state == ApprovalState.APPROVED
                && data.approvedPullHash != null
                && !data.approvedPullHash.equals(info.currentPullHash)) {
            // Someone pushed after the approval. It only stays approved if it still auto-approves,
            // otherwise it goes back to waiting for a person.
            if (ExternalApprovalHelper.evaluateAutoApproval(info)) {
                data.approvedBy = AUTO_APPROVAL;
                data.approvedPullHash = info.currentPullHash;
            } else {
                data.reset();
            }
            changed = true;
        }
        if (changed) {
            try {
                data.save(job);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to persist approval data for " + job.getFullName(), e);
            }
        }
        // Mirror the state every time, not just when it changed: a restart or a re-index can leave
        // the job enabled while its record still says pending, and then the PR looks free to build.
        applyApprovalState(job, data.state);
        // Keep GitHub's status in step with a still-pending PR. This runs on every re-scan (not just
        // when the state changed), so re-indexing re-asserts a missing or failed status. We remember
        // the commit we notified for so we do it at most once per commit, not on every page render.
        if (data.state == ApprovalState.PENDING
                && info.currentPullHash != null
                && !info.currentPullHash.equals(data.notifiedPullHash)
                && ExternalApprovalHelper.notifyAwaitingApproval(job, info)) {
            data.notifiedPullHash = info.currentPullHash;
            try {
                data.save(job);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to persist approval data for " + job.getFullName(), e);
            }
        }
        return data;
    }
}
