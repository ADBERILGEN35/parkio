import http.client, json, os, sys, tempfile, uuid, hashlib, time
sys.path.insert(0, '.')
from pathlib import Path
root = Path(tempfile.mkdtemp(dir=sys.argv[1]))
os.environ.update({"PARKIO_SLACK_BIZ_ENABLED":"true","PARKIO_SLACK_BIZ_WEBHOOK_URL":"https://mock.invalid/hook",
 "PARKIO_SLACK_BIZ_DATA_DIR":str(root/"state"),"PARKIO_SLACK_BIZ_ENVIRONMENT":"acceptance",
 "PARKIO_SLACK_BIZ_WAITLIST_INBOX":str(root/"inbox"),"PARKIO_SLACK_BIZ_MAX_ATTEMPTS":"3","PARKIO_SLACK_BIZ_LEASE_SECONDS":"0.2"})
from slack_biz.config import load_config
from slack_biz.store import DeliveryStore
from slack_biz.waitlist_inbox import WaitlistInboxConsumer
from slack_biz.delivery import DeliveryWorker
from slack_biz.transport import SlackWebhookTransport
from slack_biz.adapters import WAITLIST_PRODUCER
cfg = load_config()
print("activation_ready", cfg.activation_ready())
store = DeliveryStore(cfg.db_path, lease_seconds=0.2)
(root/"inbox").mkdir(parents=True)
env = {"contractVersion":1,"eventId":str(uuid.uuid4()),"eventType":"waitlist.subscription_confirmed","occurredAt":"2026-09-22T11:04:05Z",
 "environment":"acceptance","producer":WAITLIST_PRODUCER,"dedupKey":"waitlist:subscription_confirmed:"+hashlib.sha256(b"x").hexdigest()}
(root/"inbox"/"waitlist-a.json").write_text(json.dumps(env))
print("poll", WaitlistInboxConsumer(cfg, store, root/"inbox").poll_once().__dict__)
sends = 0
class Op:
    def open(self, req, timeout=None):
        global sends; sends += 1
        raise http.client.IncompleteRead(b'o')
for cycle in range(5):
    w = DeliveryWorker(cfg, store, SlackWebhookTransport(opener=Op()), worker_id=f"w{cycle}", acquire_lock=False)
    try:
        w.process_once()
    except Exception as e:
        print("cycle", cycle, "worker crashed:", type(e).__name__)
    time.sleep(0.3)
r = store._conn.execute("select status, attempts from delivery_queue").fetchone()
print("sends", sends, "row", dict(r))
