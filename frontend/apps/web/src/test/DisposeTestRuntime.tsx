import { useEffect } from 'react';
import type { WebAppRuntime } from '@/app/runtime';

export function DisposeTestRuntime({ runtime }: { runtime: WebAppRuntime }) {
  useEffect(() => () => runtime.dispose(), [runtime]);
  return null;
}
