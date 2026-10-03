# Contribution Guide

Thank you for considering contributing to Pontik Cluster!

## Bug Reports

If you discover a problem, please open an issue. Your issue should contain a title and
a clear description of the problem. You should also include as much relevant
information as possible, such as your engine and database versions and the steps that
reproduce the issue. The goal is to make it easy for yourself - and others - to
replicate the bug and develop a fix.

Questions are welcome in the issue tracker, too.

## Pull Requests

We are not accepting unsolicited pull requests just yet. If you would like to work on
something, please open an issue first so we can talk it through together.

## Commit Messages

We follow [Conventional Commits](https://www.conventionalcommits.org/). You should
write your commit subject in the imperative mood and keep it within 72 characters:

```
<type>(optional scope): <description>
```

Every commit should end with two trailers, in this order:

```
Assisted-By: <tool>/<version>
Signed-off-by: Your Name <your.email@example.com>
```

## AI-Assisted Contributions

You are welcome to use AI tools when contributing to Pontik Cluster. We only ask that
you tell us. The `Assisted-By` trailer names the tool that helped you write the change.
If you wrote the change by hand, use `Assisted-By: none`. Please don't leave the
trailer out.

## Developer Certificate of Origin

The `Signed-off-by` trailer certifies the
[Developer Certificate of Origin](https://developercertificate.org/). In other words,
you are confirming that you wrote the change, or otherwise have the right to submit it
under the project's license. You don't have to type it yourself, as `git commit -s`
will add it for you.

## Building Locally

The repository ships a devcontainer with JDK 21 and the Gradle wrapper, so there is
nothing to install on your machine besides Docker. Open the repository in VS Code and
choose *Reopen in Container*. After that, you may run `./gradlew` inside the container.

## Security Vulnerabilities

Please review [our security policy](SECURITY.md) on how to report security
vulnerabilities. Please don't report them in a public issue.
