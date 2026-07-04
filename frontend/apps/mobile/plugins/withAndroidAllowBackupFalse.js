const { withAndroidManifest } = require('expo/config-plugins');

/**
 * Sets `android:allowBackup="false"` on the generated Android manifest.
 *
 * expo-secure-store already excludes the keystore-backed prefs from backups
 * (see `secure_store_backup_rules`), but the rest of app data (WebView map
 * caches, file caches) has no reason to travel through device-to-device or
 * cloud backup either. Disabling backup entirely removes that surface.
 *
 * Local config plugin (no dependency): referenced from `app.json` `plugins`,
 * applied by `expo prebuild`.
 */
module.exports = function withAndroidAllowBackupFalse(config) {
  return withAndroidManifest(config, (mod) => {
    const application = mod.modResults.manifest.application?.[0];
    if (application) {
      application.$['android:allowBackup'] = 'false';
    }
    return mod;
  });
};
