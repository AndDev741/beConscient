import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './styles.css';
import { App } from './App';

async function start() {
  // Mock mode serves fixture data so the page can be previewed without a backend.
  // Both checks are replaced at build time, so a normal build drops the mock entirely.
  if (import.meta.env.MODE === 'mock' || import.meta.env.VITE_MOCK === '1') {
    const { installMock } = await import('./mock');
    installMock();
  }
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}

void start();
