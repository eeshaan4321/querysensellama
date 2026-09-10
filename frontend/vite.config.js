import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  // jsdom + the jest-dom matchers, so components can be asserted on as
  // rendered output rather than as return values. `globals` keeps the test
  // files free of per-file vitest imports.
  // Vitest does not pick up the React plugin's JSX handling for test files
  // on its own here, and the components under test use the automatic
  // runtime (no `import React`). Stating it explicitly keeps the test
  // files transformed the same way the production build transforms them.
  esbuild: { jsx: 'automatic' },
  test: {
    environment: 'jsdom',
    setupFiles: './src/setupTests.js',
    globals: true,
  },
})
