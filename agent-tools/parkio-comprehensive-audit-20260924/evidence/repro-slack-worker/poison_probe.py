import http.client, sys
sys.path.insert(0, '.')
from slack_biz.transport import SlackWebhookTransport
class Op:
    def __init__(self, exc): self.exc = exc
    def open(self, req, timeout=None): raise self.exc
for exc in [http.client.IncompleteRead(b'ok'), http.client.BadStatusLine('x'), http.client.LineTooLong('header')]:
    try:
        r = SlackWebhookTransport(opener=Op(exc)).send("https://mock.invalid/x", {"text": "t"})
        print(type(exc).__name__, "->", r.classification)
    except Exception as e:
        print(type(exc).__name__, "-> UNCAUGHT", type(e).__name__)
