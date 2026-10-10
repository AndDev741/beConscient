import { defineConfig, mergeConfig } from 'vitest/config';
import viteConfig from './vite.config';

// Fixed zone so hour labels and dates in tests do not depend on the machine
process.env.TZ = 'Europe/Lisbon';

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      include: ['src/**/*.test.{ts,tsx}'],
      restoreMocks: true,
      unstubGlobals: true,
    },
  }),
);
