/**
 * Offline/online probe for region-first Gemini verification of false-reject examples.
 * Usage:
 *   $env:PARKIO_AI_VISION_GEMINI_API_KEY='...'
 *   node scripts/probe-claimed-region-examples.mjs
 */
import fs from 'node:fs';
import path from 'node:path';

const KEY = process.env.PARKIO_AI_VISION_GEMINI_API_KEY || '';
const MODEL = process.env.PARKIO_AI_VISION_GEMINI_MODEL || 'gemini-2.5-flash-lite';

const EXAMPLES = [
  {
    id: 'A',
    path: path.join(process.env.USERPROFILE || '', 'Pictures', 'Screenshots', 'Ekran görüntüsü 2026-07-20 230234.png'),
    // Open asphalt of empty bay ABOVE the yellow barrier (no barrier intersection).
    claimedRegion: { x: 0.34, y: 0.48, width: 0.32, height: 0.22 },
  },
  {
    id: 'B',
    path: path.join(process.env.USERPROFILE || '', 'Pictures', 'Screenshots', 'Ekran görüntüsü 2026-07-20 230337.png'),
    // Open paved area to the left of the white Peugeot.
    claimedRegion: { x: 0.04, y: 0.42, width: 0.30, height: 0.40 },
  },
];

const PROMPT = `You are validating a photo submitted to Parkio.
REGION-FIRST: evaluate the claimed region as the free parking space.
Nearby cars/barriers elsewhere must NOT cause NOT_A_PARKING_SPOT when the marked region is usable.
Ambiguity → UNCERTAIN. Respond ONLY with JSON:
{"verdict":"LIKELY_PARKING|UNCERTAIN|NOT_A_PARKING_SPOT","confidence":0-1,"reasonCode":"...","claimedRegionAssessment":"FREE|BLOCKED|UNCERTAIN","vehicleFitEstimate":"FITS|TIGHT|UNCERTAIN|TOO_SMALL","obstructionAssessment":"NONE_IN_TARGET|PARTIAL_IN_TARGET|BLOCKING_TARGET|NEARBY_ONLY","legalityAccessAssessment":"OK|UNCERTAIN|RESTRICTED"}`;

function mapProduct(verdict, reasonCode, confidence) {
  const rejectCodes = new Set([
    'NO_PLAUSIBLE_SPACE','TARGET_PHYSICALLY_BLOCKED','CLEARLY_RESTRICTED_AREA',
    'UNRELATED_SUBJECT','SCREENSHOT_OR_SYNTHETIC','TOO_DARK_OR_BLURRY',
  ]);
  const forceUncertain = new Set([
    'NEARBY_BARRIER_NOT_BLOCKING_TARGET','LEGALITY_UNCERTAIN',
    'POSSIBLE_SPACE_UNCERTAIN_WIDTH','POSSIBLE_SPACE_UNCLEAR_ACCESS','WHOLE_IMAGE_NO_REGION',
  ]);
  let v = verdict;
  if (v === 'LIKELY_PARKING' && confidence < 0.75) v = 'UNCERTAIN';
  if (v === 'NOT_A_PARKING_SPOT') {
    if (forceUncertain.has(reasonCode) || !rejectCodes.has(reasonCode) || confidence < 0.75) {
      v = 'UNCERTAIN';
    }
  }
  if (v === 'LIKELY_PARKING') return { decision: 'ACCEPT', aiStatus: 'PASSED', parking: 'ACTIVE' };
  if (v === 'NOT_A_PARKING_SPOT') return { decision: 'REJECT', aiStatus: 'FAILED', parking: 'REJECTED' };
  return { decision: 'REVIEW', aiStatus: 'WARNING', parking: 'PENDING_REVIEW' };
}

async function analyze(example) {
  const bytes = fs.readFileSync(example.path);
  const b64 = bytes.toString('base64');
  const mime = example.path.toLowerCase().endsWith('.png') ? 'image/png' : 'image/jpeg';
  const r = example.claimedRegion;
  const body = {
    contents: [{
      role: 'user',
      parts: [
        { text: `${PROMPT}\nCLAIMED REGION: x=${r.x} y=${r.y} width=${r.width} height=${r.height}` },
        { inlineData: { mimeType: mime, data: b64 } },
      ],
    }],
    generationConfig: {
      responseMimeType: 'application/json',
      temperature: 0,
      maxOutputTokens: 1024,
    },
  };
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent?key=${KEY}`;
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const raw = await res.text();
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}: ${raw.slice(0, 500)}`);
  }
  const json = JSON.parse(raw);
  const text = json?.candidates?.[0]?.content?.parts?.[0]?.text;
  const parsed = JSON.parse(text);
  const product = mapProduct(parsed.verdict, parsed.reasonCode, parsed.confidence);
  return { raw: json, parsed, product };
}

async function main() {
  if (!KEY) {
    console.error('MISSING_KEY: set PARKIO_AI_VISION_GEMINI_API_KEY');
    process.exit(2);
  }
  const out = [];
  for (const ex of EXAMPLES) {
    if (!fs.existsSync(ex.path)) {
      out.push({ id: ex.id, error: `missing file: ${ex.path}` });
      continue;
    }
    console.error(`Running example ${ex.id}...`);
    const result = await analyze(ex);
    out.push({
      id: ex.id,
      claimedRegion: ex.claimedRegion,
      path: ex.path,
      ...result,
    });
  }
  console.log(JSON.stringify(out, null, 2));
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});