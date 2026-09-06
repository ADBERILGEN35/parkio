# Real Slack acceptance: manual operator action only

Normal push/PR observability CI validates rules, receiver rendering and routing,
and FIRING/RESOLVED behavior against the isolated local webhook catcher. It has
no real Slack secret. The catcher script clears inherited Slack configuration
and fixes its delivery target to the in-compose catcher.

Real external delivery is a separate `alerting-operator-acceptance.yml` workflow,
triggered only by `workflow_dispatch`. The operator must explicitly provide:

`confirm_real_slack_delivery=ALERTING-REAL-SLACK-ACCEPTANCE`

Default/missing/wrong confirmation fails in the secret-free authorization job.
The secret-bearing job requires authorization success plus the exact manual event
and confirmation; the delivery step repeats this condition. The script itself
also checks intent before secret access or resource creation. The token is a
non-secret acknowledgement, not proof that any operator gate has been approved.

Only a separately authorized operator should run that workflow. It sends one
real FIRING and one real RESOLVED notification to the existing destination.
Do not run it as part of source certification. No production operational alert
receiver, channel, webhook, or alerting behavior is changed by this restriction.

Before a source push, run `python3 scripts/test_alerting_workflow_safety.py`.
This checks parsed workflow semantics and mutation cases, executes only the
authorization helper for the accepted input, and never invokes confirmed delivery.
After push, verify no operator-acceptance run exists for the candidate SHA.
