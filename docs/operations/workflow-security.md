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

To add or update one, resolve the tag to its commit and write that SHA:

```bash
gh api repos/<owner>/<repo>/git/ref/tags/<tag> --jq '.object'
gh api repos/<owner>/<repo>/git/tags/<sha> --jq '.object.sha'   # an annotated tag points to a tag object first
```

For a docker image, use `docker buildx imagetools inspect <image>:<tag>` and take the digest.
Dependabot's `github-actions` updates keep SHA pins and their comments current, once it is
enabled for this ecosystem (U08).

## Inputs never go straight into a script

A `run:` script or a step `shell:` must not contain an expression that reads any of:
- a dispatch or `workflow_call` input (`inputs.*`, `github.event.inputs.*`);
- any field of the triggering event (`github.event.*`);
- the head branch name (`github.head_ref`).

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
