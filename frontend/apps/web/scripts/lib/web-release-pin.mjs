/**
 * Parse effective Compose `services.<name>.image` from a YAML overlay.
 * Comments are ignored so a digest that appears only in a comment cannot pass.
 *
 * Supports the flat overlay shape used by Parkio pin files:
 *   services:
 *     web:
 *       image: registry/name@sha256:...
 */
export function parseComposeServiceImage(yamlText, serviceName = 'web') {
  const lines = String(yamlText).split(/\r?\n/);
  let inServices = false;
  let inTargetService = false;
  let servicesIndent = null;
  let serviceIndent = null;

  for (const raw of lines) {
    const line = raw.replace(/\t/g, '  ');
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;

    const indentMatch = line.match(/^ */);
    const indent = indentMatch ? indentMatch[0].length : 0;
    const content = trimmed.replace(/\s+#.*$/, '').trim();

    if (!inServices) {
      if (content === 'services:') {
        inServices = true;
        servicesIndent = indent;
      }
      continue;
    }

    if (indent <= servicesIndent && content.endsWith(':') && !content.startsWith('services:')) {
      // left services block (name: parkio, etc.)
      inServices = false;
      inTargetService = false;
      continue;
    }

    if (indent === servicesIndent + 2 && content.endsWith(':')) {
      const name = content.slice(0, -1).trim();
      inTargetService = name === serviceName;
      serviceIndent = indent;
      continue;
    }

    if (!inTargetService) continue;

    if (indent <= serviceIndent) {
      inTargetService = false;
      continue;
    }

    const imageMatch = content.match(/^image:\s*(.+)$/);
    if (imageMatch) {
      return imageMatch[1].trim().replace(/^['"]|['"]$/g, '');
    }
  }

  return null;
}

export function extractDigest(imageRef) {
  if (!imageRef) return null;
  const match = /@(sha256:[a-f0-9]{64})$/i.exec(imageRef);
  return match ? match[1].toLowerCase() : null;
}

export function readComposeProductionFiles(listText) {
  return String(listText)
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith('#'));
}

/**
 * Last listed compose file that defines services.web.image wins (overlay precedence).
 * @param {Array<{ path: string, text: string }>} files
 */
export function resolveEffectiveWebImage(files) {
  let winner = null;
  for (const file of files) {
    const image = parseComposeServiceImage(file.text, 'web');
    if (image) {
      winner = { path: file.path, image, digest: extractDigest(image) };
    }
  }
  return winner;
}

export function assertWebReleasePinContract({
  pinText,
  composeFilesListText,
  composeFileContents,
  expectedPinPath = 'docker/docker-compose.web-release-pin.yml',
}) {
  const pinImage = parseComposeServiceImage(pinText, 'web');
  if (!pinImage) {
    throw new Error('web release pin has no effective services.web.image');
  }
  const pinDigest = extractDigest(pinImage);
  if (!pinDigest) {
    throw new Error(`web release pin image is not digest-pinned: ${pinImage}`);
  }

  const listed = readComposeProductionFiles(composeFilesListText);
  if (!listed.includes(expectedPinPath)) {
    throw new Error(`${expectedPinPath} missing from compose.production.files`);
  }
  if (listed[listed.length - 1] !== expectedPinPath) {
    throw new Error(
      `${expectedPinPath} must be last in compose.production.files for overlay precedence (got last=${listed[listed.length - 1]})`,
    );
  }

  const effective = resolveEffectiveWebImage(composeFileContents);
  if (!effective) {
    throw new Error('no services.web.image found across production compose overlays');
  }
  if (effective.path !== expectedPinPath) {
    throw new Error(
      `effective web image comes from ${effective.path}, expected ${expectedPinPath}`,
    );
  }
  if (effective.image !== pinImage) {
    throw new Error(
      `effective web image mismatch: effective=${effective.image} pin=${pinImage}`,
    );
  }

  return { pinImage, pinDigest, effective };
}

/** Fail when the effective pin image digest is not the reviewed digest (comments ignored). */
export function assertPinDigestEquals(pinText, expectedDigest) {
  const image = parseComposeServiceImage(pinText, 'web');
  if (!image) {
    throw new Error('web release pin has no effective services.web.image');
  }
  const digest = extractDigest(image);
  const expected = String(expectedDigest).toLowerCase();
  if (digest !== expected) {
    throw new Error(`effective pin digest ${digest ?? '(none)'} != expected ${expected}`);
  }
  return { image, digest };
}
