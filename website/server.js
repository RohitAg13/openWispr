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

const server = http.createServer((req, res) => {
  // strip query string, decode, normalize, prevent path traversal
  let urlPath = decodeURIComponent((req.url || '/').split('?')[0]);
  if (urlPath === '/') urlPath = '/index.html';
  const filePath = path.normalize(path.join(ROOT, urlPath));
  if (!filePath.startsWith(ROOT)) {
    res.writeHead(403); res.end('Forbidden'); return;
  }

  fs.readFile(filePath, (err, data) => {
    if (err) {
      // SPA-style fallback to index.html for unknown routes
      fs.readFile(path.join(ROOT, 'index.html'), (e2, home) => {
        if (e2) { res.writeHead(404); res.end('Not found'); return; }
        res.writeHead(200, { 'Content-Type': TYPES['.html'] });
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
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`OpenWispr landing page serving on :${PORT}`);
});
