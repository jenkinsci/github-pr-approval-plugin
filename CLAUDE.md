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
2. Open a fork PR and scan — the job should be disabled and marked *Pending Approval*.
3. Click **Approve** on the Pending Approval page — the job builds.
4. With *Require approval for new commits* on: approve, then push a commit. It goes back to pending.
5. With your fork login in *Auto-approval users*: a matching PR builds without asking.
6. MCP (optional): with the MCP Server plugin installed, connect an MCP client (auth as a user with
   `Configure`) and check `getPendingApprovals` lists the pending PR and `approvePullRequest` builds
   it — the same result as steps 2–3.

## Architecture

Five classes in `src/main/java/io/jenkins/plugins/github_pr_approval/`:

- `TrustExternalApproval` — the policy; extends
  `ForkPullRequestDiscoveryTrait.GitHubForkTrustPolicy` and is found by the Trust dropdown via
  `SCMHeadAuthority`. Holds the options.
- `ExternalApprovalHelper` — walks branch job → `MultiBranchProject` → `GitHubSCMSource` to
  answer "is this a fork PR under this policy?", and makes the GitHub calls. Also
  `addAutoApprovalUser`, which edits the policy's auto-approval list and saves the project.
- `ExternalApprovalInfo` — value object from that walk (PR number, author, current pull hash,
  source, options).
- `PendingApprovalAction` — the approval page plus the five `@Extension`s that run the
  lifecycle: `ActionFactory` (attach the page), `ApprovalItemListener` (mirror state onto the job,
  on creation and at startup), `ApprovalScanListener` (settle every PR once a scan finishes),
  `ApprovalSpender` (reset after a build when approval-per-commit is on), `ApprovalQueueGuard`
  (block). The reusable `approve(job, hash, who)` and `isBlocked(job, info)` statics are shared
  with the MCP tools.
- `PendingApprovalMcpTools` — optional MCP Server integration (`@OptionalExtension(requirePlugins =
  "mcp-server")`, so it only loads when that plugin is present). Two tools: `getPendingApprovals`
  (optionally scoped to one project by full name) lists blocked fork PRs; `approvePullRequest`
  approves one and can add its author to the auto-approval list. Runs as the MCP caller and checks
  `Item.CONFIGURE`, like the web UI.

Jelly views live under `src/main/resources/io/jenkins/plugins/github_pr_approval/<ClassName>/`.

Nothing here patches GitHub Branch Source: the policy is contributed as an `@Extension` against a
*released* version of it, which is the main design constraint on this plugin — anything that would
need a change inside GitHub Branch Source is off the table. Branch API is what lets us reach the
branch job and toggle its disabled flag; it comes in as a plugin dependency, so users never install
it by hand.

The MCP Server plugin is an *optional* dependency (pinned version), only for `PendingApprovalMcpTools`.
It sets the floor for `jenkins.version` (2.541.3) and the BOM version (`6783.v88c6c30f4b_db_`, the one
the pinned MCP Server is built against, so its jackson3-api/git/workflow-cps floors are met and it
starts in `InjectedTest`). Bump the pin and the BOM together.

The policy was first proposed inside GitHub Branch Source itself
([PR #1556](https://github.com/jenkinsci/github-branch-source-plugin/pull/1556)) and was extracted
here as a separate plugin instead — see commit `b8959a1`.

## Gotchas

- **`ApprovalQueueGuard` is what enforces the policy, not the disabled job flag.** Branch
  indexing schedules a new fork PR's first build in the same pass and can win the race against
  the job being disabled, and *Build Now* walks straight past a disabled job. The disabled flag
  is only what the user sees. Do not weaken the guard.
- **The guard runs while the build queue is locked.** Keep it to in-memory checks plus the one
  `pending-approval.xml` read — never a GitHub call, never a write.
- **`resolveApprovalData` is the only writer of approval state.** It can cost a GitHub API call
  (label lookup), so it runs only when the record is missing or the approved commit moved on.
- **A fork PR's head commit is unknown the first time we see it.** branch-api schedules the first
  build *before* it writes `scm-last-seen-revision-hash.xml`, so `getCurrentPullHash` returns null
  on that first pass. `resolveApprovalData` deliberately records nothing then: an approval pinned
  to a null hash would never expire, and we could not tell GitHub which commit is waiting.
- **Nothing re-applies the disabled flag on its own, and nothing tells you a scan happened.** No
  item listener fires for a re-index, so a restart or a scan would otherwise leave a pending PR
  looking like a normal, buildable, green job. Three things hold it together: `resolveApprovalData`
  mirrors the state on *every* call, `ApprovalItemListener.onLoaded` re-asserts it at startup, and
  `ApprovalScanListener` re-resolves every branch once indexing finishes. That last one hangs off
  `ExecutorListener` because branch indexing runs as a queue task on the multibranch project
  itself — there is no scan-finished hook in branch-api. It has to run *after* the scan, since
  during one the revision on disk is still the previous commit.
- Approval state persists as `pending-approval.xml` in the job's root dir, which is why it
  survives restarts.
- Auto-approval users match case-insensitively and are exempt from re-approval; auto-approval
  labels are checked only when the PR is first seen.

## Conventions

- MIT licence header on every Java file (Spotless enforces it).
- Comments and Javadoc: plain and human, explaining *why*. Existing files set the tone.
- Commit messages: short and human, no `Co-Authored-By` trailers.
- Tests are JUnit 5: `@WithJenkins` on the class with a `JenkinsRule` injected into `@BeforeEach`
  and the test methods, not the JUnit 4 `@Rule`. `PendingApprovalMcpTools` is tested by calling its
  methods directly under `JenkinsRule` (no MCP transport needed).

## Release setup (JEP-229, not live yet)

`.github/workflows/cd.yaml`, `Jenkinsfile` and `.mvn/` incrementals config are in place. Three
one-time infra steps remain, with drafts in `release-setup/`:

1. Request the `jenkinsci/github-pr-approval-plugin` repo via Jenkins hosting
   (`release-setup/hosting-request.md`).
2. PR `release-setup/permissions/plugin-github-pr-approval.yml` to
   [`jenkins-infra/repository-permissions-updater`](https://github.com/jenkins-infra/repository-permissions-updater).
3. Confirm the org-level `MAVEN_USERNAME` / `MAVEN_TOKEN` secrets and the Jenkins-infra GitHub App,
   so the `check_run`-triggered `cd.yaml` can publish.
