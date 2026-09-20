-- Parkio NR log pilot filters (ops.log.v1)
-- Untrusted log bodies cannot override trusted service / environment / release_id
-- (those are set by record_modifier after this filter).

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

local function contains_sensitive(msg)
  local checks = {
    "[Aa]uthorization%s*[:=]%s*Bearer%s+%S+",
    "[Cc]ookie%s*[:=]%s*%S+",
    "[Aa]ccess[_%-]?[Tt]oken%s*[:=]%s*%S+",
    "[Rr]efresh[_%-]?[Tt]oken%s*[:=]%s*%S+",
    "[Pp]assword%s*[:=]%s*%S+",
    "[Cc]lient[_%-]?[Ss]ecret%s*[:=]%s*%S+",
    "BEGIN%s+[%w%s]*PRIVATE KEY",
    "X%-Amz%-Signature=",
    "X%-Amz%-Credential=",
    "lat[itude]*%s*[:=]%s*%-?%d+%.%d%d%d%d",
    "lon[gitude]*%s*[:=]%s*%-?%d+%.%d%d%d%d",
  }
  for i = 1, #checks do
    if string.find(msg, checks[i]) then
      return true
    end
  end
  return false
end

local function redact(msg)
  local out = msg
  local n
  out, n = string.gsub(out, "Bearer%s+%S+", "Bearer [REDACTED]")
  local changed = n > 0
  out, n = string.gsub(out, "[Pp]assword%s*[:=]%s*%S+", "password=[REDACTED]")
  changed = changed or n > 0
  out, n = string.gsub(out, "[Cc]lient[_%-]?[Ss]ecret%s*[:=]%s*%S+", "client_secret=[REDACTED]")
  changed = changed or n > 0
  out, n = string.gsub(out, "[Aa]ccess[_%-]?[Tt]oken%s*[:=]%s*%S+", "access_token=[REDACTED]")
  changed = changed or n > 0
  out, n = string.gsub(out, "[Rr]efresh[_%-]?[Tt]oken%s*[:=]%s*%S+", "refresh_token=[REDACTED]")
  changed = changed or n > 0
  out, n = string.gsub(out, "X%-Amz%-Signature=%S+", "X-Amz-Signature=[REDACTED]")
  changed = changed or n > 0
  out, n = string.gsub(out, "X%-Amz%-Credential=%S+", "X-Amz-Credential=[REDACTED]")
  changed = changed or n > 0
  if #out > 16384 then
    out = string.sub(out, 1, 16384) .. "...[TRUNCATED]"
    changed = true
  end
  return out, changed
end

local function clean_id(v)
  if v == nil or v == "" or v == "-" then
    return nil
  end
  return v
end

local function parse_spring(msg)
  local level = string.match(msg, "^%s*(%u+)%s+")
  if level == nil then
    level = string.match(msg, "^%d%d%d%d%-%d%d%-%d%d[%sT]%S+%s+(%u+)%s+")
  end
  if level == nil then
    level = "UNKNOWN"
  end

  local correlation_id = clean_id(string.match(msg, "correlationId=([%w%-]+)"))
  local trace_id = clean_id(string.match(msg, "traceId=([%w]+)"))
  local span_id = clean_id(string.match(msg, "spanId=([%w]+)"))
  local error_code = string.match(msg, "errorCode=([%w%._%-]+)")
    or string.match(msg, "error_code=([%w%._%-]+)")

  local parse_status = "ok"
  if level == "UNKNOWN" then
    parse_status = "unparsed"
  end

  return level, msg, correlation_id, trace_id, span_id, error_code, parse_status
end

function process_record(tag, timestamp, record)
  local service = service_from_path(record["pilot_path"])
  if service == nil or pilot_services[service] ~= true then
    return -1, 0, 0
  end

  local raw = record["log"] or record["message"] or record["msg"]
  if type(raw) ~= "string" then
    return 1, timestamp, {
      service = service,
      level = "UNKNOWN",
      message = "[UNPARSEABLE_RECORD]",
      parse_status = "malformed",
      export_decision = "export",
      redaction = "none",
    }
  end

  -- Hard-exclude high-risk canaries / private key material from export.
  if contains_sensitive(raw) then
    return -1, 0, 0
  end

  local redacted, changed = redact(raw)
  local level, message, correlation_id, trace_id, span_id, error_code, parse_status =
    parse_spring(redacted)

  local out = {
    service = service,
    level = level,
    message = message,
    parse_status = parse_status,
    export_decision = "export",
    redaction = changed and "applied" or "none",
  }
  if correlation_id then out["correlation_id"] = correlation_id end
  if trace_id then out["trace_id"] = trace_id end
  if span_id then out["span_id"] = span_id end
  if error_code then out["error_code"] = error_code end
  return 1, timestamp, out
end
