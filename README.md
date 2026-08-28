# GitHub PR Approval Plugin

Adds an **External approval required** trust policy for GitHub fork pull requests.

With the [GitHub Branch Source plugin](https://github.com/jenkinsci/github-branch-source-plugin),
trusting fork PRs is all-or-nothing (nobody, everybody, contributors, or write access). This plugin
adds a middle ground: fork PRs are discovered as usual, but each one waits for a person to approve
it before it builds. Handy for public repos where you want CI on fork PRs, but only after someone
has taken a look.

## Setting it up

In your multibranch project, under *Discover pull requests from forks*, set **Trust** to
**External approval required**:

![The fork PR trust policy set to "External approval required", with its options](docs/images/trust-policy-config.png)

### Options

- **Require approval for new commits** — the approval only covers the commit you approved. When a new
  commit is pushed, the next build needs a fresh approval.
- **Auto-approval users** — GitHub logins (comma-separated) whose PRs are approved automatically.
  Matched ignoring case, like GitHub does. These authors never have to ask again.
- **Auto-approval labels** — labels (comma-separated) that approve a PR automatically. Checked once,
  when the PR is first seen.

## How it works

A new fork PR is discovered like any other, but its job is created disabled and carries a
**Pending Approval** action:

![The Pull Requests list showing a disabled PR job with its "Pending Approval" menu entry](docs/images/pending-approval-job.png)

Open **Pending Approval** to see who opened the PR, which commit is up for approval, and the
**Approve** button. Anyone with `Configure` permission on the job can use it:

![The "External Approval Required" page showing PR number, author, status and an Approve button](docs/images/approval-page.png)

Approving enables the job and starts a build. Once approved, the same page offers
**Revoke Approval** to take it back. The approval is saved next to the job, so it sticks across
restarts.

## What actually blocks the build

The disabled job is what you see; a
[`Queue.QueueDecisionHandler`](src/main/java/io/jenkins/plugins/github_pr_approval/PendingApprovalAction.java)
is what enforces it. Disabling alone is not enough — branch indexing schedules a new fork PR's first
build in the same pass, and that build can win the race against the job being disabled. A manual
*Build Now* or a re-trigger would get past a disabled job too. Jenkins asks every queue decision
handler before it queues anything, so refusing there blocks the build no matter what triggered it.

## How it relates to GitHub Branch Source

This plugin does not modify GitHub Branch Source. It contributes the policy as an `@Extension`
(`TrustExternalApproval extends ForkPullRequestDiscoveryTrait.GitHubForkTrustPolicy`), which the
fork-PR Trust dropdown discovers automatically via `SCMHeadAuthority`. It depends on a released
GitHub Branch Source plugin and on Branch API (to reach the branch job and toggle its disabled flag).

The policy was originally proposed as
[github-branch-source-plugin#1556](https://github.com/jenkinsci/github-branch-source-plugin/pull/1556)
and lives here as a separate plugin instead.

## Building

```bash
mvn clean install     # build, format check (Spotless), and tests
mvn hpi:run           # run a local Jenkins with the plugin installed
```

### Trying it out

1. In a multibranch project, add *Discover pull requests from forks* with trust set to
   **External approval required**.
2. Open a fork PR and scan — the job should be disabled and marked *Pending Approval*.
3. Click **Approve** on the Pending Approval page — the job builds.
4. Optional: turn on *Require approval for new commits*, approve, then push a commit. It goes back
   to pending.
5. Optional: add your fork login to *Auto-approval users*. A matching PR builds without asking.

## Releasing (JEP-229 continuous delivery)

The in-repo `.github/workflows/cd.yaml`, `Jenkinsfile`, and `.mvn/` incrementals config are ready.
To turn on automated releases, the following one-time infra steps are needed (drafts are in
`release-setup/`):

1. **Hosting** — request the `jenkinsci/github-pr-approval-plugin` repository via the Jenkins hosting
   process (see `release-setup/hosting-request.md`).
2. **Release permissions** — open a PR to
   [`jenkins-infra/repository-permissions-updater`](https://github.com/jenkins-infra/repository-permissions-updater)
   adding `release-setup/permissions/plugin-github-pr-approval.yml`.
3. **Secrets** — confirm the org-level `MAVEN_USERNAME` / `MAVEN_TOKEN` secrets and the Jenkins-infra
   GitHub App are in place so the `check_run`-triggered `cd.yaml` can publish.

## License

MIT — see [LICENSE.txt](LICENSE.txt).
