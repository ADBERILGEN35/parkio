# CodeQL alerts #1, #5 and #6: dispositions (U15, CL-F08)

**Analysed on:** api `6b285f2c` (the api instance of #5/#6) and master `8d0ee2b4` (the master
instance of all three). The flagged UploadPage regions are byte-identical on both commits.
**Out of scope:** #7 (separately recorded decision), #2–#4 (already dismissed), dependency
alerts, CodeQL query changes.
**Not done here:** no alert is dismissed. A dismissal needs the security owner's approval and
should quote this document, the alert ID and the SHA.

| Alert | Rule | Location | Open on | Disposition |
|---|---|---|---|---|
| **#5** | `js/xss-through-dom` (high) | `frontend/apps/web/src/pages/UploadPage.tsx:433` (photo step preview `<img src={previewUrl}>`) | api `6b285f2c`, master `8d0ee2b4` | **Not exploitable**: false positive. Recommend dismissal ("false positive") with owner approval. Regression test added. |
| **#6** | `js/xss-through-dom` (high) | `frontend/apps/web/src/pages/UploadPage.tsx:879` (review step preview `<img src={previewUrl}>`) | api `6b285f2c`, master `8d0ee2b4` | Same as #5: the same value reaches the same kind of sink. |
| **#1** | `js/clear-text-storage-of-sensitive-data` (high) | `frontend/apps/web/src/auth/pendingProfile.ts:18` (`sessionStorage.setItem(…, JSON.stringify(profile))` with `phoneNumber`) | master `8d0ee2b4` only | **Real on master, fixed on api.** No dismissal. It closes when api reaches master; the `refs/pull/44/merge` instance already reports it fixed. |

## #5 and #6: data flow

1. **Source.** `<input type="file">` → `onFileChange` → `setFile(selected)`. The `File` object comes from the user's own file picker.
2. **Transform.** A `useEffect` on `file` calls `URL.createObjectURL(file)` and stores the result in `previewUrl`. On cleanup it calls `revokeObjectURL`.
3. **Sink.** `<img src={previewUrl}>` in the photo step (#5) and in `ReviewStep` (#6).

Why this is not exploitable:

- `createObjectURL` returns a URL that the **browser** mints: `blob:<origin>/<uuid>`. Nothing from the file's name, type or bytes can turn it into a `javascript:` or `data:text/html` URL, or into markup.
- The sink is an `<img>` `src` attribute set by React through `setAttribute`. It is not HTML parsing, and an image `src` never executes script.
- The file **name** is the only attacker-shaped text, and only the user who picked the file controls it. It is rendered through React text nodes (`{fileName ?? …}`), so it is escaped. It never reaches `src`, `innerHTML` or `dangerouslySetInnerHTML`; there is no such sink in the file.
- No other user's input reaches this page: the preview exists only for the local, not yet uploaded file.

CodeQL models `File` → object URL → `src` as DOM text reaching an HTML-interpreting sink. It does not model `createObjectURL` as a URL that the browser generates.

### Evidence

- **Unit regression test** (committed). `UploadPage.test.tsx`, "builds both photo previews from an object URL and renders a hostile file name as text", uses the file name `"><img src=x onerror="…">.jpg`. It asserts:
  - `createObjectURL` is called with that `File`;
  - both previews carry exactly the object URL;
  - the name appears as literal text;
  - no `img[src="x"]` exists and no probe variable is set.

  A mutant that takes `src` from the file name fails it (`agent-tools/parkio-u15-codeql-dispositions/vitest-mutant-src-from-name.log`).
- **Real-browser check** (Chromium, evidence only, not committed). The same hostile name is chosen through `setInputFiles` on `/upload`, with the API mocked:
  - the preview `src` matches `^blob:http://localhost:<port>/<uuid>$`;
  - the name renders once as text;
  - there is no `img[src="x"]`, no probe and no dialog.

  The spec, log and screenshot are in `agent-tools/parkio-u15-codeql-dispositions/`.

## #1: data flow and status

On master `8d0ee2b4`, `setPendingProfile` serialises the whole pending profile, including `phoneNumber`, into `sessionStorage`. The finding is correct for that code.

On api it is fixed:
- `16c1f158` keeps the pending `phoneNumber` out of sessionStorage;
- `21e150e5` prompts phone re-entry after a pending-profile reload;
- `d2e8924e` binds the pending profile to the registering account.

On api, the phone now lives in module memory only, legacy payloads are scrubbed, and `pendingProfile.ts` documents this. All three commits are ancestors of api and not of master. That is why the alert is open only on master and is reported fixed on `refs/pull/44/merge`.

## Analysis success is not alert state

The Security CI CodeQL jobs, `CodeQL (javascript-typescript)` among them, succeed on api while #5 and #6 stay open. A green analysis job means the scan ran. It does not close or dismiss these alerts. Alert state changes only through a fix that the next analysis observes, or through an approved dismissal.
