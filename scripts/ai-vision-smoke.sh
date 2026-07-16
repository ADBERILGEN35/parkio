#!/bin/sh
# Parkio — OPERATOR-ONLY smoke test for the real vision provider (Gemini).
#
# Sends three local images through the same prompt + structured-output schema the
# ai-validation-service uses and prints the verdicts. Costs a few provider calls.
#
# Usage:
#   PARKIO_AI_VISION_GEMINI_API_KEY=... ./scripts/ai-vision-smoke.sh \
#       parking.jpg unrelated.jpg ambiguous.jpg
#
# Expected outcomes:
#   image 1 (real empty parking space)  -> LIKELY_PARKING
#   image 2 (keyboard/desk/unrelated)   -> NOT_A_PARKING_SPOT
#   image 3 (dark/blurry/ambiguous)     -> UNCERTAIN (any non-LIKELY outcome is safe)
#
# Safety: the API key is read from the environment and sent only as a request
# header; it is never echoed, logged, or written to disk. Do not run in CI.
set -eu

MODEL="${PARKIO_AI_VISION_GEMINI_MODEL:-gemini-2.5-flash-lite}"
BASE_URL="${PARKIO_AI_VISION_GEMINI_BASE_URL:-https://generativelanguage.googleapis.com}"

if [ "$#" -ne 3 ]; then
  echo "usage: $0 <parking-image> <unrelated-image> <ambiguous-image>" >&2
  exit 2
fi
if [ -z "${PARKIO_AI_VISION_GEMINI_API_KEY:-}" ]; then
  echo "ERROR: PARKIO_AI_VISION_GEMINI_API_KEY is not set (export it in this shell only; never commit it)" >&2
  exit 2
fi

PROMPT="You are validating a photo submitted to Parkio, an app where people share real on-street or lot parking spots that are currently available for others. Classify the photo into exactly one verdict: LIKELY_PARKING - the photo shows credible, real-world visual evidence of a physical location where a vehicle could plausibly park now: an empty roadside space, an empty marked bay, or a parking area with an identifiable available space. The scene must look like a real outdoor/garage location, not a screen, print, or synthetic render. NOT_A_PARKING_SPOT - the photo clearly does not substantiate a real parking location: keyboards, desks, selfies, people, food, pets, screenshots, memes, documents, screens, indoor objects, a vehicle close-up with no visible parking context, a car with no plausible available space, or obviously synthetic or copied content. UNCERTAIN - the photo is too dark, blurry, obstructed, or ambiguous; or it may show a parking area but no credible available space can be established; or you are not confident. Never choose LIKELY_PARKING just because a car or road is visible. When in doubt, choose UNCERTAIN. Respond only with the JSON object - no explanation."

mime_of() {
  case "$1" in
    *.png|*.PNG) echo "image/png" ;;
    *.webp|*.WEBP) echo "image/webp" ;;
    *) echo "image/jpeg" ;;
  esac
}

classify() {
  img="$1"
  label="$2"
  if [ ! -f "$img" ]; then
    echo "$label: SKIP (file not found: $img)" >&2
    return 0
  fi
  b64=$(base64 < "$img" | tr -d '\n')
  payload=$(mktemp)
  trap 'rm -f "$payload"' EXIT
  cat > "$payload" <<EOF
{"contents":[{"parts":[{"text":"$PROMPT"},{"inlineData":{"mimeType":"$(mime_of "$img")","data":"$b64"}}]}],
 "generationConfig":{"temperature":0,"maxOutputTokens":256,"responseMimeType":"application/json",
 "responseSchema":{"type":"OBJECT","properties":{"verdict":{"type":"STRING","enum":["LIKELY_PARKING","UNCERTAIN","NOT_A_PARKING_SPOT"]},"confidence":{"type":"NUMBER"},"reasonCode":{"type":"STRING","enum":["EMPTY_SPACE_VISIBLE","PARKING_AREA_NO_CLEAR_SPACE","NO_PARKING_CONTEXT","UNRELATED_SUBJECT","SCREENSHOT_OR_SYNTHETIC","TOO_DARK_OR_BLURRY","OBSTRUCTED_OR_AMBIGUOUS","OTHER"]}},"required":["verdict","confidence","reasonCode"]}}}
EOF
  # The key travels only in the header; curl output is the response body only.
  result=$(curl -sS -X POST \
      -H "x-goog-api-key: $PARKIO_AI_VISION_GEMINI_API_KEY" \
      -H "Content-Type: application/json" \
      --data-binary @"$payload" \
      "$BASE_URL/v1beta/models/$MODEL:generateContent" || true)
  rm -f "$payload"
  trap - EXIT
  # Extract the inner JSON text; fall back to showing a redacted hint on failure.
  verdict=$(printf '%s' "$result" | tr -d '\n' \
      | sed -n 's/.*"text"[[:space:]]*:[[:space:]]*"\({[^"]*\|{.*verdict[^}]*}\)".*/\1/p' \
      | sed 's/\\//g')
  if [ -z "$verdict" ]; then
    verdict=$(printf '%s' "$result" | tr -d '\n' | sed -n 's/.*\("verdict"[^,}]*\).*/\1/p')
  fi
  if [ -z "$verdict" ]; then
    echo "$label ($img): PROVIDER CALL FAILED (status/error withheld from output; check quota/key)" >&2
    return 1
  fi
  echo "$label ($img): $verdict"
}

echo "Model: $MODEL"
classify "$1" "A. parking   (expect LIKELY_PARKING)"
classify "$2" "B. unrelated (expect NOT_A_PARKING_SPOT)"
classify "$3" "C. ambiguous (expect UNCERTAIN)"
