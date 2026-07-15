const { withAndroidManifest, withAndroidStyles } = require('expo/config-plugins');

/**
 * Keeps generated Android resources compatible with Parkio's minSdk and with
 * devices that do not have a camera (for example, some ChromeOS devices).
 *
 * expo-splash-screen currently writes the API 33 splash behavior attribute to
 * the base values folder. Android safely ignores that style item on older API
 * levels, but lint requires the API intent to be explicit. The camera feature
 * declaration prevents CAMERA permission from making camera hardware an
 * implicit Google Play installation requirement.
 */
module.exports = function withAndroidCompatibility(config) {
  config = withAndroidStyles(config, (mod) => {
    for (const style of mod.modResults.resources.style ?? []) {
      if (style.$?.name !== 'Theme.App.SplashScreen') continue;

      const behavior = style.item?.find(
        (item) => item.$?.name === 'android:windowSplashScreenBehavior'
      );
      if (behavior) {
        behavior.$['tools:targetApi'] = '33';
      }
    }
    return mod;
  });

  return withAndroidManifest(config, (mod) => {
    const manifest = mod.modResults.manifest;
    const features = manifest['uses-feature'] ?? [];
    const camera = features.find(
      (feature) => feature.$?.['android:name'] === 'android.hardware.camera'
    );

    if (camera) {
      camera.$['android:required'] = 'false';
    } else {
      features.push({
        $: {
          'android:name': 'android.hardware.camera',
          'android:required': 'false',
        },
      });
      manifest['uses-feature'] = features;
    }

    return mod;
  });
};
