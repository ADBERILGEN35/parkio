/**
 * Babel config for the Parkio mobile-v2 app.
 *
 * - `babel-preset-expo` is the canonical RN/Expo preset (Hermes-aware, supports
 *   the new architecture and Expo Router).
 * - `react-native-worklets/plugin` MUST be listed last; it powers Reanimated v4
 *   worklets (Reanimated moved its Babel plugin into react-native-worklets).
 *
 * Cache key includes EXPO_PUBLIC_* so release/profile env changes invalidate
 * transforms (babel-preset-expo inlines those values at transform time).
 */
module.exports = function (api) {
  api.cache.using(
    () =>
      [
        process.env.EXPO_PUBLIC_APP_ENV ?? '',
        process.env.EXPO_PUBLIC_API_BASE_URL ?? '',
        process.env.EXPO_PUBLIC_SMART_RETURN_ENABLED ?? '',
      ].join('|'),
  );
  return {
    presets: ['babel-preset-expo'],
    plugins: ['react-native-worklets/plugin'],
  };
};
