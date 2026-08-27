# GitHub PR Approval Plugin

Adds an **External approval required** trust policy for GitHub fork pull requests.

With the [GitHub Branch Source plugin](https://github.com/jenkinsci/github-branch-source-plugin),
trusting fork PRs is all-or-nothing (nobody, everybody, contributors, or write access). This plugin
adds a middle ground: fork PRs are discovered as usual, but each one waits for a person to approve
it before it builds. Handy for public repos where you want CI on fork PRs, but only after someone
has taken a look.

## How it works

- In *Discover pull requests from forks*, set the fork trust policy to **External approval required**.
- A new fork PR shows up as a disabled job marked **Pending Approval**, and it won't build on its own.
- Someone with `Configure` permission approves it from the job's **Pending Approval** page (or revokes
  it). Approving enables the job and starts a build. The approval is saved next to the job, so it
  sticks across restarts.

### Options

- **Require approval for new commits** — the approval only covers the commit you approved. When a new
  commit is pushed, the next build needs a fresh approval.
- **Auto-approval users** — GitHub logins (comma-separated) whose PRs are approved automatically.
- **Auto-approval labels** — labels (comma-separated) that approve a PR automatically.

## How it relates to GitHub Branch Source

This plugin does not modify GitHub Branch Source. It contributes the policy as an `@Extension`
(`TrustExternalApproval extends ForkPullRequestDiscoveryTrait.GitHubForkTrustPolicy`), which the
fork-PR Trust dropdown discovers automatically via `SCMHeadAuthority`. It depends on a released
GitHub Branch Source plugin and on Branch API (to reach the branch job and toggle its disabled flag).

## Building

```bash
mvn clean install     # build, format check (Spotless), and tests
mvn hpi:run           # run a local Jenkins with the plugin installed
```

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
