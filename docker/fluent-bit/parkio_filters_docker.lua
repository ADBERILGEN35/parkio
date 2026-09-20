-- Docker/forward path: service must come from compose label / container name,
-- never from free-text log body.

local pilot_services = {
  ["gateway-service"] = true,
  ["auth-service"] = true,
  ["parking-service"] = true,
}

-- Reuse file-mode logic by setting pilot_path synthetically.
dofile("/fluent-bit/etc/parkio/parkio_filters.lua")

function process_docker_record(tag, timestamp, record)
  local service = record["com.docker.compose.service"]
    or record["container_name"]
    or record["service"]
  if type(service) == "string" then
    service = string.gsub(service, "^/", "")
    service = string.gsub(service, "^parkio%-", "")
  end
  if pilot_services[service] ~= true then
    return -1, 0, 0
  end
  record["pilot_path"] = "/var/log/parkio-pilot/" .. service .. "/forward.log"
  return process_record(tag, timestamp, record)
end
