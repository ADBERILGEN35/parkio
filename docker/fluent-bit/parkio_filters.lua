-- Parkio New Relic log pilot filters (ops.log.v1).
--
-- Output is an allowlisted record. Untrusted log bodies cannot set service,
-- environment, release or collector identity. Regexes reduce known exposure;
-- they are not a proof that arbitrary free text is safe. High-risk records are
-- dropped as a unit rather than partially exported.

local pilot_services = {
  ["gateway-service"] = true,
  ["auth-service"] = true,
  ["parking-service"] = true,
}

local function service_from_path(path)
  if type(path) ~= "string" then
    return nil
  end
  return string.match(path, "/parkio%-pilot/([^/]+)/")
end

local function clean_id(value)
  if value == nil or value == "" or value == "-" then
    return nil
  end
  return value
end

local function contains_jwt(raw)
  for candidate in string.gmatch(raw, "[%w_%-]+%.[%w_%-]+%.[%w_%-]+") do
    local first, second, third = string.match(candidate, "^([%w_%-]+)%.([%w_%-]+)%.([%w_%-]+)$")
    if first and #first >= 10 and #second >= 10 and #third >= 8 then
      return true
    end
  end
  return false
end

local function hard_drop(raw)
  local checks = {
    "[Aa]uthorization%s*[:=]",
    "[Cc]ookie%s*[:=]",
    "[Ss]et%-[Cc]ookie%s*[:=]",
    "BEGIN%s+[%w%s]*PRIVATE KEY",
    "PARKIO_PROHIBITED_CANARY",
    "lat[itude]*%s*[:=]%s*%-?%d+%.%d%d%d%d",
    "lon[gitude]*%s*[:=]%s*%-?%d+%.%d%d%d%d",
  }
  for i = 1, #checks do
    if string.find(raw, checks[i]) then
      return true
    end
  end
  return contains_jwt(raw)
end

local function replace(out, pattern, replacement, changed)
  local count
  out, count = string.gsub(out, pattern, replacement)
  return out, changed or count > 0
end

local function redact_url(url)
  local query_at = string.find(url, "?", 1, true)
  if query_at then
    return string.sub(url, 1, query_at) .. "[REDACTED_QUERY]"
  end
  return url
end

local function redact(raw)
  local out = raw
  local changed = false
  local count

  -- Subscriber identifiers are not useful operator dimensions.
  out, changed = replace(out, "[%w%._%+%-]+@[%w%.%-]+%.[%a][%a]+", "[EMAIL_REDACTED]", changed)
  out, changed = replace(out, "[Ee]mail[Hh]ash%s*[:=]%s*[%w_%-]+", "emailHash=[REDACTED]", changed)

  -- Known credentials and token assignments. Preserve the key name, never value.
  local assignments = {
    {"[Pp]assword%s*[:=]%s*[^%s,;]+", "password=[REDACTED]"},
    {"[Cc]lient[_%-]?[Ss]ecret%s*[:=]%s*[^%s,;]+", "client_secret=[REDACTED]"},
    {"[Aa]ccess[_%-]?[Tt]oken%s*[:=]%s*[^%s,;]+", "access_token=[REDACTED]"},
    {"[Rr]efresh[_%-]?[Tt]oken%s*[:=]%s*[^%s,;]+", "refresh_token=[REDACTED]"},
    {"[Vv]erification[_%-]?[Tt]oken%s*[:=]%s*[^%s,;]+", "verification_token=[REDACTED]"},
    {"[Cc]onfirmation[_%-]?[Tt]oken%s*[:=]%s*[^%s,;]+", "confirmation_token=[REDACTED]"},
    {"[Ww]ithdrawal[_%-]?[Tt]oken%s*[:=]%s*[^%s,;]+", "withdrawal_token=[REDACTED]"},
    {"[Ww]ithdraw[_%-]?[Tt]oken%s*[:=]%s*[^%s,;]+", "withdraw_token=[REDACTED]"},
    {"[Rr]esend[_%-]?[Kk]ey%s*[:=]%s*[^%s,;]+", "resend_key=[REDACTED]"},
    {"[Aa]pi[_%-]?[Kk]ey%s*[:=]%s*[^%s,;]+", "api_key=[REDACTED]"},
    {"X%-Amz%-Signature=[^%s&,]+", "X-Amz-Signature=[REDACTED]"},
    {"X%-Amz%-Credential=[^%s&,]+", "X-Amz-Credential=[REDACTED]"},
    {"re_[%w_%-]+", "[RESEND_KEY_REDACTED]"},
  }
  for i = 1, #assignments do
    out, changed = replace(out, assignments[i][1], assignments[i][2], changed)
  end

  -- Confirmation and withdrawal path tokens can appear without a query string.
  out, changed = replace(out, "/confirm/[^%s/?#]+", "/confirm/[REDACTED]", changed)
  out, changed = replace(out, "/unsubscribe/[^%s/?#]+", "/unsubscribe/[REDACTED]", changed)
  out, changed = replace(out, "/withdraw/[^%s/?#]+", "/withdraw/[REDACTED]", changed)

  -- All URL query strings are removed, including unknown future parameter names.
  local before_urls = out
  out, count = string.gsub(out, "https?://[^%s]+", redact_url)
  changed = changed or (count > 0 and out ~= before_urls)

  if #out > 8192 then
    out = string.sub(out, 1, 8192) .. "...[TRUNCATED]"
    changed = true
  end
  return out, changed
end

local function still_sensitive(message)
  local checks = {
    "[%w%._%+%-]+@[%w%.%-]+%.[%a][%a]+",
    "BEGIN%s+[%w%s]*PRIVATE KEY",
    "[Aa]uthorization%s*[:=]",
    "[Ss]et%-[Cc]ookie%s*[:=]",
    "[Cc]ookie%s*[:=]",
    "X%-Amz%-Signature=[^%[]",
    "X%-Amz%-Credential=[^%[]",
  }
  for i = 1, #checks do
    if string.find(message, checks[i]) then
      return true
    end
  end
  return contains_jwt(message)
end

local function parse_level(message)
  local level = string.match(message, "^%s*(%u+)%s+")
  if level == nil then
    level = string.match(message, "^%d%d%d%d%-%d%d%-%d%d[T ]%S+%s+(%u+)%s+")
  end
  local allowed = {TRACE=true, DEBUG=true, INFO=true, WARN=true, ERROR=true, FATAL=true}
  if allowed[level] then
    return level, "ok"
  end
  return "UNKNOWN", "unparsed"
end

local function event_name(message)
  local lower = string.lower(message)
  if string.find(lower, "waitlist confirmation delivery failed", 1, true) then
    return "waitlist.confirmation.delivery_failed"
  end
  if string.find(lower, "waitlist withdrawal notice delivery failed", 1, true) then
    return "waitlist.withdrawal.delivery_failed"
  end
  if string.find(lower, "waitlist confirmation emailed", 1, true) then
    return "waitlist.confirmation.delivered"
  end
  if string.find(lower, "waitlist withdrawal emailed", 1, true) then
    return "waitlist.withdrawal.delivered"
  end
  if string.find(lower, "nr-log-pilot synthetic marker", 1, true) then
    return "nr_log_pilot.synthetic_probe"
  end
  return nil
end

function process_record(tag, timestamp, record)
  local service = service_from_path(record["pilot_path"])
  if service == nil or pilot_services[service] ~= true then
    return -1, 0, 0
  end

  local raw = record["log"] or record["message"] or record["msg"]
  if type(raw) ~= "string" then
    return -1, 0, 0
  end
  if hard_drop(raw) then
    return -1, 0, 0
  end

  local message, changed = redact(raw)
  if still_sensitive(message) then
    return -1, 0, 0
  end
  message = string.gsub(message, "[\r\n]+$", "")

  local level, parse_status = parse_level(message)
  local correlation_id = clean_id(string.match(message, "correlationId=([%w%-]+)"))
  local trace_id = clean_id(string.match(message, "traceId=([%w]+)"))
  local span_id = clean_id(string.match(message, "spanId=([%w]+)"))
  local error_code = string.match(message, "errorCode=([%w%._%-]+)")
    or string.match(message, "error_code=([%w%._%-]+)")
  local marker = string.match(message, "pilotMarker=([%w%._%-]+)")
  local event = event_name(message)

  local out = {
    service = service,
    severity = level,
    level = level,
    message = message,
    parse_status = parse_status,
    redaction = changed and "applied" or "none",
    source_kind = record["synthetic_pilot"] == "true" and "synthetic" or "docker-logs-api",
  }
  if record["stream"] == "stdout" or record["stream"] == "stderr" then
    out["source_stream"] = record["stream"]
  end
  if correlation_id then out["correlation_id"] = correlation_id end
  if trace_id then out["trace_id"] = trace_id end
  if span_id then out["span_id"] = span_id end
  if error_code then out["error_code"] = error_code end
  if marker then out["pilot_marker"] = marker end
  if event then out["event_name"] = event end
  return 2, 0, out
end
