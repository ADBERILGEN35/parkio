| id | status | detail |
|---|---|---|
| E01 | PASS | gateway uid 10001 (+grp 10500) writes inbox 2770; relay uid 10002 owns 0700 state |
| E02 | PASS | confirm 202 → outbox EXPORTED → relay delivered=1 → 1 mock Slack post |
| E03 | PASS | repeat confirm 202, outbox rows=1, Slack posts=1 |
| E04 | PASS | file written as 10001:10500 while relay down; delivered after relay restart |
| E05 | PASS | 2 rows re-exported after simulated crash; relay suppressed by dedupKey; posts still 2 |
| E06 | PASS | SIGKILL left committed PENDING row; exported + delivered after restart |
| E07 | PASS | relay disabled: queued=1 posts unchanged; enabled → delivered |
| E08 | PASS | ops disabled: confirm 202/CONFIRMED, outbox unchanged (EXPORTED:4), inbox empty, posts 4 |
| E09 | PASS | previous image healthy on V4 schema, confirm works, history untouched (1:true,2:true,3:true,4:true) |
| E10 | PASS | PR image healthy again after rollback |
| E11 | PASS | no email/token/city/subscriber-id/email-hash/webhook in Slack payloads, relay state, inbox or logs; Slack keys=mrkdwn,text,username |

PASS=11 FAIL=0
