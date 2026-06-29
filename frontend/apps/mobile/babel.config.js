/**
 * Babel config for the Parkio mobile app.
 *
 * - `babel-preset-expo` is the canonical RN/Expo preset (Hermes-aware, supports
 *   the new architecture and Expo Router).
 * - `react-native-worklets/plugin` MUST be listed last; it powers Reanimated v4
 *   worklets (Reanimated moved its Babel plugin into react-native-worklets).
 */
module.exports = function (api) {
  api.cache(true);
  return {
    presets: ['babel-preset-expo'],
    plugins: ['react-native-worklets/plugin'],
  };
};
