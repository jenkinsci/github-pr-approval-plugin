# Jenkins hosting request

File this as a new issue in `jenkins-infra/repository-permissions-updater` (the "Hosting request"
process at https://www.jenkins.io/doc/developer/publishing/requesting-hosting/).

- **New Repository Name:** `github-pr-approval-plugin`
- **New Repository URL:** https://github.com/olamy/github-pr-approval-plugin (source to transfer to the `jenkinsci` org)
- **GitHub users to have commit permission:** olamy
- **Jenkins project users to have release permission (Artifactory):** olamy
- **groupId / artifactId:** `io.jenkins.plugins` / `github-pr-approval`

## Description

Adds a new fork pull request trust policy — *External approval required* — to the GitHub Branch
Source plugin's "Discover pull requests from forks" trait. A fork PR is discovered as usual, but its
branch job stays disabled with a *Pending Approval* marker until someone with Configure permission
approves it, at which point the job is enabled and a build is scheduled. Optional settings allow
re-approval on each new commit, and auto-approval by GitHub login or PR label.

The plugin depends on a released GitHub Branch Source plugin and contributes the policy purely as an
`@Extension` (an `SCMHeadAuthority`), so it needs no change to GitHub Branch Source. It resolves the
review discussion in jenkinsci/github-branch-source-plugin#1556, where a standalone plugin was
suggested rather than pulling `branch-api` into the branch source plugin.
