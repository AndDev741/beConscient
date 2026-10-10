// Content script for https://www.linkedin.com/*
import { createLinkedinReader } from './linkedinPage';
import { startReporter } from './reporter';
import { sendToBackground } from './send';

startReporter({
  doc: document,
  win: window,
  read: createLinkedinReader(document, location),
  send: sendToBackground,
});
