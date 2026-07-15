import { Redirect } from 'expo-router';

/** Tab chrome for My spots — reuse the richer stack screen. */
export default function MySpotsTab() {
  return <Redirect href="/(main)/my-spots" />;
}