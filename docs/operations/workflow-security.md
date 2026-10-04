# Workflow security (CL-F40)

`scripts/ci/check_workflow_security.py` runs in the *Workflow security* workflow on every change to
`.github/workflows/`. It enforces two rules.

## Third-party actions are pinned

- A third-party action is pinned to the full commit SHA of the release it was taken from, with
  the version as a comment, for example `uses: docker/login-action@c94ce9f… # v3.7.0`.
- A `docker://` action is pinned to its image digest.
- A tag can be moved to other code; a commit cannot.
- GitHub-owned actions (`actions/*`, `github/*`) and local actions stay on tags. Pinning those too
  is a separate policy choice (U08).

To add or update one, resolve the release tag all the way to its commit and write that SHA, with
the exact release as the comment:

```bash
gh api repos/<owner>/<repo>/commits/<tag> --jq .sha                  # peels every tag level
git ls-remote https://github.com/<owner>/<repo> 'refs/tags/<tag>^{}'  # the same, without the API
gh api repos/<owner>/<repo>/commits/<sha> --jq .sha                  # must print <sha> back
```

Do not stop at `git/ref/tags/<tag>` or one `git/tags/<sha>` lookup. An annotated tag points to a tag
object, and a major tag can point to another tag: `gradle/actions` `v4` → tag object → tag object
`v4.4.3` → commit. A tag object's SHA is not a commit SHA. The checker cannot tell the two apart
offline, so the last command above is the check.

For a docker image, use `docker buildx imagetools inspect <image>:<tag>` and take the digest.
Dependabot's `github-actions` ecosystem is enabled (`.github/dependabot.yml`). It updates SHA pins
together with their version comments, and it will also propose new major versions.

## Inputs never go straight into a script

A script must not contain an expression that reads any of:
- a dispatch or `workflow_call` input (`inputs`);
- the triggering event or any of its fields (`github.event`);
- the head branch name (`github.head_ref`).

The scripts are a step's `run:` and `shell:`, `defaults.run.shell` of the workflow and of each
job, and the `script` input of `actions/github-script`. Context names match in any case and in
dot or index syntax, as GitHub evaluates them: `Inputs.x`, `inputs['x']`, `github['head_ref']`
and `toJSON(github.event)` are all refused. So is the whole `github` context, as `toJSON(github)` or
the object filter `github.*`, because it contains both fields. Values passed on through `env.*`,
`steps.*.outputs` or `needs.*.outputs` are not traced; review those by hand.

Such an expression is pasted into the script before the shell parses it, so a crafted value
becomes code. Pass the value through `env:` instead and use it quoted:

```yaml
- name: Validate inputs
  env:
    DISPATCH_MODE: ${{ inputs.mode }}
  run: |
    if [ "$DISPATCH_MODE" = "hosted" ]; then …
```

Values the workflow controls (`github.sha`, `github.event_name`, `runner.temp`, …) may still be
written inline. The deploy workflows have their own, stricter guard
(`scripts/ci/test_deploy_workflow_guards.py`).
