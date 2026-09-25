# Mobile-v2 native build ownership

Canonical release APK/AAB builds use the **committed** ndroid/ tree (scripts/run-android-release.mjs -> Gradle). WP-07 lists that tree as the signing/source-of-truth surface. This is a bare/continuous-native project, not CNG-on-EAS.

pp.json still carries Expo config plugins and store-identity fields so expo prebuild can regenerate ndroid/ when an operator explicitly chooses to. EAS/Gradle will not re-apply those fields while ndroid/ is present. That is expected: the checked-in native project already contains the last prebuild output (see @generated markers in MainActivity.kt).

Do not delete ndroid/ to silence expo-doctor. Do not disable the CNG/sync doctor check. The remaining doctor finding is this ownership hybrid, not a hidden skip.
