# github-pr-approval-plugin

Jenkins plugin adding an "External approval required" fork-PR trust policy to GitHub Branch
Source. See README.md for the user-facing behaviour and screenshots.

## Commands

```bash
mvn clean install       # build + Spotless check + tests
mvn spotless:apply      # fix formatting (the build fails on unformatted code)
mvn hpi:run             # local Jenkins on http://localhost:8080/jenkins with the plugin
mvn test -Dtest=ApprovalQueueGuardTest
```

CI runs JDK 25 on Linux and JDK 21 on Windows (`Jenkinsfile`); JDK 21 locally.

### Manual verification

Under `mvn hpi:run`, against a real GitHub repo:

1. In a multibranch project, add *Discover pull requests from forks* with trust set to
   **External approval required**.
2. Open a fork PR and scan — the job stays enabled with a *Pending Approval* banner, but builds are
   refused. The *Pull Requests* list shows an *Approval* column marking it. A project admin can press
   *Build Now* for a one-off build; anyone else is blocked.
3. Click **Approve** on the Pending Approval page — the job builds.
4. With *Require approval for new commits* on: approve, then push a commit. It goes back to pending.
5. With your fork login in *Auto-approval users*: a matching PR builds without asking.
6. MCP (optional): with the MCP Server plugin installed, connect an MCP client (auth as a user with
   `Configure`) and check `getPendingApprovals` lists the pending PR and `approvePullRequests` builds
   it — the same result as steps 2–3.

## Architecture

Six classes in `src/main/java/io/jenkins/plugins/github_pr_approval/`:

- `TrustExternalApproval` — the policy; extends
  `ForkPullRequestDiscoveryTrait.GitHubForkTrustPolicy` and is found by the Trust dropdown via
  `SCMHeadAuthority`. Holds the options.
- `ExternalApprovalHelper` — walks branch job → `MultiBranchProject` → `GitHubSCMSource` to
  answer "is this a fork PR under this policy?", and makes the GitHub calls. Also
  `addAutoApprovalUser`, which edits the policy's auto-approval list and saves the project.
- `ExternalApprovalInfo` — value object from that walk (PR number, author, current pull hash,
  source, options).
- `PendingApprovalAction` — the approval page plus the five `@Extension`s that run the
  lifecycle: `ActionFactory` (attach the page), `ApprovalItemListener` (keep held PRs enabled,
  on creation and at startup), `ApprovalScanListener` (settle every PR once a scan finishes),
  `ApprovalSpender` (reset after a build when approval-per-commit is on), `ApprovalQueueGuard`
  (block — unless someone who can approve pressed *Build Now*). The reusable `approve(job, hash, who)`
  and `isBlocked(job, info)` statics are shared with the MCP tools.
- `PendingApprovalColumn` — a `ListViewColumn` that flags pending fork PRs in the multibranch
  *Branches* and *Pull Requests* tabs (the job is no longer disabled, so this is the at-a-glance list
  cue). `shownByDefault()` puts it in those synthetic views with no setup; a paired
  `DescriptorVisibilityFilter` (matched to branch-api's `BaseView`) keeps it out of ordinary dashboards.
- `PendingApprovalMcpTools` — optional MCP Server integration (`@OptionalExtension(requirePlugins =
  "mcp-server")`, so it only loads when that plugin is present). Two tools: `getPendingApprovals`
  (optionally scoped to one project by full name) lists blocked fork PRs; `approvePullRequests`
  approves a batch of them (and can add each author to the auto-approval list), reporting each job
  independently so one bad name does not abort the rest — the per-job work is in `approveOne`. Runs
  as the MCP caller and checks `Item.CONFIGURE` on the multibranch project, like the web UI; a
  permission failure still throws rather than hiding in a result.

Jelly views live under `src/main/resources/io/jenkins/plugins/github_pr_approval/<ClassName>/`.

Nothing here patches GitHub Branch Source: the policy is contributed as an `@Extension` against a
*released* version of it, which is the main design constraint on this plugin — anything that would
need a change inside GitHub Branch Source is off the table. Branch API is what lets us reach the
branch job (to re-enable it, and to cancel a build a scan queued); it comes in as a plugin dependency,
so users never install it by hand.

The MCP Server plugin is an *optional* dependency (pinned version), only for `PendingApprovalMcpTools`.
It sets the floor for `jenkins.version` (2.541.3) and the BOM version (`6783.v88c6c30f4b_db_`, the one
the pinned MCP Server is built against, so its jackson3-api/git/workflow-cps floors are met and it
starts in `InjectedTest`). Bump the pin and the BOM together.

The policy was first proposed inside GitHub Branch Source itself
([PR #1556](https://github.com/jenkinsci/github-branch-source-plugin/pull/1556)) and was extracted
here as a separate plugin instead — see commit `b8959a1`.

## Gotchas

- **`ApprovalQueueGuard` is what enforces the policy; the job is left enabled.** Branch indexing
  schedules a new fork PR's first build in the same pass, and *Build Now* would run too — the guard
  refuses at `Queue.schedule2` no matter what triggered it. The one exception: a build started by a
  user who has the project's `Item.CONFIGURE` (i.e. could approve) is let through as a one-off, via
  `triggeredByApprover`, which resolves that *specific* user's permission — never the ambient thread,
  which during indexing is the all-powerful system user. Do not weaken the guard.
- **The guard runs while the build queue is locked.** Keep it to in-memory checks plus the one
  `pending-approval.xml` read — never a GitHub call, never a write.
- **`resolveApprovalData` is the only writer of approval state.** It can cost a GitHub API call
  (label lookup), so it runs only when the record is missing or the approved commit moved on.
- **A fork PR's head commit is unknown the first time we see it.** branch-api schedules the first
  build *before* it writes `scm-last-seen-revision-hash.xml`, so `getCurrentPullHash` returns null
  on that first pass. `resolveApprovalData` deliberately records nothing then: an approval pinned
  to a null hash would never expire, and we could not tell GitHub which commit is waiting.
- **The guard is permission-aware but stays cheap.** `triggeredByApprover` resolves a user and checks
  a permission only when a build is *already* going to be blocked and carries a real `UserIdCause`; the
  branch-indexing hot path (no user) returns before that. Still no GitHub call and no write.
- **We no longer disable the job, so a queued build is cancelled by hand.** When an approved PR gets a
  new commit under *require approval for new commits*, branch indexing queues that build before writing
  the revision, so the guard waves it through against the old approved hash. `resolveApprovalData` calls
  `cancelQueuedBuild` when it resets — the disable used to do this as a side effect.
- **Re-enabling on load skips dead branches.** `ensureBuildableForApproval` undoes a stale disable left
  by an older version but leaves a `Branch.Dead` job alone (`ExternalApprovalHelper.isDeadBranch`), so
  it does not fight branch-api's own disable of a closed PR.
- **The job is left enabled; nothing tells you a scan happened.** No item listener fires for a
  re-index. The guard blocks pending PRs on its own, but three things still keep the job enabled and
  the GitHub status in step (and undo a disable an older version left behind): `resolveApprovalData`
  re-resolves and re-enables on *every* call, `ApprovalItemListener.onLoaded` does the same at startup,
  and `ApprovalScanListener` re-resolves every branch once indexing finishes. That last one hangs off
  `ExecutorListener` because branch indexing runs as a queue task on the multibranch project
  itself — there is no scan-finished hook in branch-api. It has to run *after* the scan, since
  during one the revision on disk is still the previous commit.
- Approval state persists as `pending-approval.xml` in the job's root dir, which is why it
  survives restarts.
- Auto-approval users match case-insensitively and are exempt from re-approval; auto-approval
  labels are checked only when the PR is first seen.
- **Approval is gated on the *project's* `Item.CONFIGURE`, not the branch job's.** The branch/PR
  child is a computed job, and under project-based matrix authorization a grant on the multibranch
  project (or even global Administer) may not reach it — so a project admin would otherwise be
  wrongly denied. `PendingApprovalAction.doApprove`/`doReject` and the MCP `approveOne` (behind
  `approvePullRequests`) all check `ExternalApprovalHelper.getApprovalInfo(job).context` (the
  project), falling back to the job only if the project can't be found. The guard's one-off *Build Now*
  bypass (`triggeredByApprover`) checks the same `Item.CONFIGURE` on the same `context`.

## Conventions

- MIT licence header on every Java file (Spotless enforces it).
- Comments and Javadoc: plain and human, explaining *why*. Existing files set the tone.
- Commit messages: short and human, no `Co-Authored-By` trailers.
- Tests are JUnit 5: `@WithJenkins` on the class with a `JenkinsRule` injected into `@BeforeEach`
  and the test methods, not the JUnit 4 `@Rule`. `PendingApprovalMcpTools` is tested by calling its
  methods directly under `JenkinsRule` (no MCP transport needed).

## Release setup (JEP-229, not live yet)

`.github/workflows/cd.yaml`, `Jenkinsfile` and `.mvn/` incrementals config are in place.
