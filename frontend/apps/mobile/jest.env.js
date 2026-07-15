// Tests must declare their API target explicitly, just like every real build
// profile. `.invalid` is reserved and cannot resolve to a live service.
process.env.EXPO_PUBLIC_APP_ENV = 'development';
process.env.EXPO_PUBLIC_API_BASE_URL = 'https://api.test.invalid/api/v1';
