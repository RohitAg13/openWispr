// Minimal zero-dependency static file server for the OpenWispr landing page.
// Railway (Nixpacks) detects package.json and runs `npm start` -> `node server.js`.
// Binds to the platform-provided PORT on 0.0.0.0.
const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = process.env.PORT || 3000;
const ROOT = __dirname;

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.ico': 'image/x-icon',
  '.json': 'application/json; charset=utf-8',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.txt': 'text/plain; charset=utf-8',
};

// Optional host consolidation. Three hosts serve this site — openwispr.dev,
// www.openwispr.dev, and the *.up.railway.app subdomain Railway never retires — which is a
// split ranking rather than redundancy. Setting CANONICAL_HOST 301s the other two to it.
//
// Deliberately opt-in and unset by default: turning it on before the apex has a DNS record
// would redirect the working www host to a dead one and take the site down. Set it only once
// `dig +short openwispr.dev` returns an answer. 301s are cached hard by browsers, so verify
// the target resolves before enabling.
const CANONICAL_HOST = process.env.CANONICAL_HOST || '';

const server = http.createServer((req, res) => {
  // Nothing a client sends may reach the process boundary as an exception. This handler runs
  // outside any promise chain, so a synchronous throw here is an uncaughtException, and an
  // uncaughtException in a single-process server is a full outage — which is exactly how a
  // scanner probing "/.%00env.production" took the site down.
  try {
    handle(req, res);
  } catch (e) {
    console.error('request failed:', req.method, req.url, e);
    if (!res.headersSent) res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Server error');
  }
});

function handle(req, res) {
  if (CANONICAL_HOST) {
    const host = (req.headers.host || '').split(':')[0];
    if (host && host !== CANONICAL_HOST) {
      res.writeHead(301, { Location: `https://${CANONICAL_HOST}${req.url || '/'}` });
      res.end();
      return;
    }
  }

  // decodeURIComponent throws URIError on malformed input — a bare "/%" is enough.
  let urlPath;
  try {
    urlPath = decodeURIComponent((req.url || '/').split('?')[0]);
  } catch {
    res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Bad request');
    return;
  }

  // fs.readFile validates its path argument *synchronously*, so a NUL byte throws a TypeError
  // before the callback exists to catch it. Reject the byte instead of relying on that.
  if (urlPath.includes('\0')) {
    res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Bad request');
    return;
  }

  if (urlPath === '/') urlPath = '/index.html';
  const filePath = path.normalize(path.join(ROOT, urlPath));

  // `startsWith(ROOT)` alone is a prefix test, not a containment test: with ROOT="/app" it
  // accepts "/app-secrets/x". Require the separator (or an exact match).
  if (filePath !== ROOT && !filePath.startsWith(ROOT + path.sep)) {
    res.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Forbidden');
    return;
  }

  // Dotfiles are what the scanners are after (.env, .git/config). Never serve them — and don't
  // fall through to the index.html handler either, because answering 200 for /.env is a
  // misleading reply to a question we should simply refuse.
  if (path.basename(filePath).startsWith('.')) {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Not found');
    return;
  }

  fs.readFile(filePath, (err, data) => {
    if (err) {
      // Unknown route: serve the landing page as a friendly body, but under a 404. Returning
      // 200 here made every probed URL look like a real page to crawlers — a soft 404.
      fs.readFile(path.join(ROOT, 'index.html'), (e2, home) => {
        if (e2) {
          res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
          res.end('Not found');
          return;
        }
        res.writeHead(404, { 'Content-Type': TYPES['.html'], 'Cache-Control': 'no-cache' });
        res.end(home);
      });
      return;
    }
    const ext = path.extname(filePath).toLowerCase();
    const headers = { 'Content-Type': TYPES[ext] || 'application/octet-stream' };
    // cache static assets; keep HTML fresh
    headers['Cache-Control'] = ext === '.html' ? 'no-cache' : 'public, max-age=3600';
    res.writeHead(200, headers);
    res.end(data);
  });
}

// Belt and braces. The guards above close the known holes; this makes the *class* survivable,
// so the next unanticipated one is a logged error rather than another outage. Safe for a static
// file server: there is no in-flight state worth protecting by dying.
process.on('uncaughtException', (e) => console.error('uncaughtException:', e));
process.on('unhandledRejection', (e) => console.error('unhandledRejection:', e));

server.listen(PORT, '0.0.0.0', () => {
  console.log(`OpenWispr landing page serving on :${PORT}`);
});
