const rawGitSha = process.env.EXPO_PUBLIC_GIT_SHA;
const rawBuildTimestamp = process.env.EXPO_PUBLIC_BUILD_TIMESTAMP;

export const buildInfo = {
  gitSha: rawGitSha && /^[0-9a-f]{7,40}$/i.test(rawGitSha) ? rawGitSha.toLowerCase() : 'unverified',
  timestamp: rawBuildTimestamp || 'unverified',
  marker: `parkio-build-sha:${rawGitSha || 'unverified'}`,
} as const;

