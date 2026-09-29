"""Safety: mention injection prevention, sensitive-field stripping, escaping."""

from __future__ import annotations

import re
from typing import Any

# Slack special mentions / mass-notify patterns
_MENTION_RE = re.compile(
    r"(?i)(^|[^a-z0-9_])@(?:channel|here|everyone|slackbot)\b"
    r"|<@(?:channel|here|everyone|U[A-Z0-9]+|W[A-Z0-9]+|S[A-Z0-9]+)>"
    r"|<!subteam\^[^>]+>"
    r"|<!everyone>|<!channel>|<!here>"
)

# Fields that must never appear in Slack payloads.
# waitlist_display_name is intentionally absent: that key is the allowlisted
# waitlist name passed to the dedicated renderer only.
SENSITIVE_KEY_FRAGMENTS = frozenset(
    {
        "email",
        "password",
        "token",
        "secret",
        "authorization",
        "cookie",
        "ssn",
        "full_name",
        "fullname",
        "firstname",
        "lastname",
        "phone",
        "address",
        "presigned",
        "sas",
        "credential",
        "apikey",
        "api_key",
        "refresh_token",
        "access_token",
        "webhook",
        "latitude",
        "longitude",
        "lat",
        "lon",
        "raw_user",
    }
)

_EMAIL_RE = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
_URL_CRED_RE = re.compile(r"(?i)(https?://)([^/\s:@]+):([^/\s@]+)@")
_PRESIGNED_RE = re.compile(r"(?i)[?&](X-Amz-|sig=|Signature=|sv=|se=|sp=)")


def escape_slack_text(text: str) -> str:
    """Escape Slack mrkdwn special characters in untrusted content."""
    if text is None:
        return ""
    out = str(text)
    out = out.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return out


def neutralize_mentions(text: str) -> str:
    """Prevent mass-mention / user-mention injection from event content."""
    if not text:
        return text

    def _repl(m: re.Match[str]) -> str:
        full = m.group(0)
        # Keep leading delimiter if present
        if full and full[0] not in "@<" and not full.startswith("<"):
            return full[0] + full[1:].replace("@", "@\u200b")
        return full.replace("@", "@\u200b").replace("<@", "<@\u200b")

    return _MENTION_RE.sub(_repl, text)


def redact_emails(text: str) -> str:
    return _EMAIL_RE.sub("[redacted-email]", text)


def redact_credential_urls(text: str) -> str:
    text = _URL_CRED_RE.sub(r"\1[redacted]@", text)
    if _PRESIGNED_RE.search(text):
        # Drop query string entirely when presigned markers present
        if "?" in text:
            return text.split("?", 1)[0] + "?[redacted-query]"
    return text


def sanitize_text(text: str) -> str:
    text = redact_emails(str(text))
    text = redact_credential_urls(text)
    text = neutralize_mentions(text)
    return escape_slack_text(text)


def key_looks_sensitive(key: str) -> bool:
    k = key.lower().replace("-", "_")
    if k in SENSITIVE_KEY_FRAGMENTS:
        return True
    return any(frag in k for frag in SENSITIVE_KEY_FRAGMENTS)


def strip_sensitive_dict(data: dict[str, Any], *, depth: int = 0) -> dict[str, Any]:
    if depth > 6:
        return {}
    out: dict[str, Any] = {}
    for k, v in data.items():
        if key_looks_sensitive(str(k)):
            continue
        if isinstance(v, dict):
            out[str(k)] = strip_sensitive_dict(v, depth=depth + 1)
        elif isinstance(v, list):
            out[str(k)] = [
                strip_sensitive_dict(i, depth=depth + 1) if isinstance(i, dict) else i
                for i in v[:20]
            ]
        elif isinstance(v, str):
            out[str(k)] = sanitize_text(v)[:500]
        else:
            out[str(k)] = v
    return out


def reject_event_supplied_destination(payload: dict[str, Any]) -> None:
    """Raise if the event tries to supply its own Slack destination."""
    banned = (
        "webhook_url",
        "webhook",
        "slack_webhook",
        "channel_id",
        "channel",
        "slack_channel",
        "api_url",
        "destination_url",
    )
    for key in banned:
        if key in payload and payload[key] not in (None, "", []):
            # Allow only logical route names under "route" if it's an approved token
            if key == "channel" and isinstance(payload[key], str):
                # channel field in events is forbidden entirely for Y03
                raise ValueError(
                    f"event-supplied destination field rejected: {key}"
                )
            if key != "route":
                raise ValueError(f"event-supplied destination field rejected: {key}")
