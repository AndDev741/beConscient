// Content script for https://www.youtube.com/*
import { startReporter } from './reporter';
import { sendToBackground } from './send';
import { createYoutubeReader, watchYoutube } from './youtubePage';

startReporter({
  doc: document,
  win: window,
  read: createYoutubeReader(document, location),
  send: sendToBackground,
  watch: (check) => watchYoutube(document, check),
});
