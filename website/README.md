# OpenWispr landing page

The marketing site for OpenWispr — a single, self-contained static page
(`index.html`) served by a tiny zero-dependency Node server (`server.js`).

```
website/
├── index.html     # the entire landing page (inline styles, vanilla-JS interactivity)
├── server.js      # zero-dependency static file server (binds to $PORT)
├── package.json   # `npm start` → node server.js
└── railway.json   # Nixpacks build + start command
```

## Run locally

```bash
cd website
npm start          # → http://localhost:3000
```

No dependencies to install — `server.js` uses only Node's standard library
(Node 18+).

## Deploy to Railway

This page lives in the `website/` subfolder of the OpenWispr repo, so point the
Railway service at that folder:

1. **New Project → Deploy from GitHub repo** and pick this repository.
2. In the service **Settings → Build**, set **Root Directory** to `website`.
3. That's it. Railway (Nixpacks) detects `package.json`, runs `npm install`
   (no-op — no deps) and `npm start`. The server binds to the `PORT` Railway
   injects. No environment variables are required.
4. Add a custom domain under **Settings → Networking** when ready.

## Editing

`index.html` is fully self-contained: all styles are inline and the four
interactive widgets (capability explorer, voice-cleanup toggle, select-&-polish
tabs, FAQ accordion) are driven by a small vanilla-JS block at the bottom of the
file. Fonts load from Google Fonts. There is no build step.

Download/Star links point at the GitHub repo and its Releases page — update them
in `index.html` if the canonical repo URL changes.
