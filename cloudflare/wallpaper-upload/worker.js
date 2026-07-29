/**
 * Wallpaper upload endpoint — the write half of the Cloudflare R2 library.
 *
 * The manager page used to POST files at the `admin-upload` Supabase Edge Function, which
 * wrote them into Supabase Storage. The fleet re-downloading that library is what blew past
 * the free bandwidth quota, so the files now live in R2 and this Worker is what puts them
 * there. It deliberately does NOT touch the database: the manager already writes the
 * `wallpapers` row itself under the admin's own session (RLS policy "wp admin insert"), so
 * this Worker needs no service-role key and holds no secret at all — the R2 binding below is
 * the only capability it has.
 *
 * Auth: the caller must present the manager's Supabase session token AND be the one admin
 * account. Without this the bucket would be an open drop box for anyone who found the url.
 * SUPABASE_ANON_KEY is public by design (it ships inside the APK and the manager page); it is
 * an api-gateway identifier, not a credential, and grants nothing on its own.
 */

const SUPABASE_URL = 'https://ihgmqwzdpugdzddobhbc.supabase.co';
const SUPABASE_ANON_KEY = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImloZ21xd3pkcHVnZHpkZG9iaGJjIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODI2NTI4ODAsImV4cCI6MjA5ODIyODg4MH0.xG0ch3u5rcbb81pSa8wmybeEYj8kz2LjCGQM_-Xg6Yk';
const ADMIN_ID = '5b8e1336-ce54-4dd9-bd23-243158c178fe';
const PUBLIC_BASE = 'https://pub-3108628f0bc04bb4a97214eb7732e284.r2.dev';

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, content-type, x-file-name, x-prefix',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
  'Access-Control-Max-Age': '86400',
};

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, 'Content-Type': 'application/json' },
  });
}

export default {
  async fetch(req, env) {
    if (req.method === 'OPTIONS') return new Response('ok', { headers: CORS });
    if (req.method !== 'POST') return json({ error: 'method not allowed' }, 405);

    const token = (req.headers.get('Authorization') || '').replace('Bearer ', '').trim();
    if (!token) return json({ error: 'no token' }, 401);

    // Ask Supabase who this token belongs to. Verifying the signature ourselves would mean
    // holding the JWT secret; asking GoTrue costs one request and also catches a token that
    // was revoked or has already expired.
    let user;
    try {
      const res = await fetch(`${SUPABASE_URL}/auth/v1/user`, {
        headers: { Authorization: `Bearer ${token}`, apikey: SUPABASE_ANON_KEY },
      });
      if (!res.ok) return json({ error: 'invalid session' }, 401);
      user = await res.json();
    } catch (e) {
      return json({ error: 'auth check failed: ' + String((e && e.message) || e) }, 502);
    }
    if (!user || user.id !== ADMIN_ID) return json({ error: 'not authorized' }, 403);

    // x-file-name is URI-encoded so Arabic names survive header transport.
    const rawName = req.headers.get('x-file-name');
    if (!rawName) return json({ error: 'missing file name' }, 400);
    let name = rawName;
    try { name = decodeURIComponent(rawName); } catch (_e) { /* keep as sent */ }

    // The caller picks a UUID name, but never trust it with the key: a '/' or '..' would let a
    // stale or hostile page write outside the wallpapers prefix, and a bare name would let one
    // customer's upload land on another's key. Strip to a leaf and refuse anything odd.
    name = name.split(/[\\/]/).pop();
    if (!name || name === '.' || name === '..' || name.length > 200) {
      return json({ error: 'bad file name' }, 400);
    }

    // Two prefixes, mirroring the two buckets this replaced. They differ in one rule: a
    // wallpaper is never overwritten, an apk may be. A wallpaper name is a UUID, so a
    // collision means something is wrong and one customer's picture is about to land on
    // another's car — fail loudly. An apk name is derived from its version, and re-uploading
    // a build you just rebuilt is a normal thing to do.
    const prefix = (req.headers.get('x-prefix') || 'wallpapers').trim();
    if (prefix !== 'wallpapers' && prefix !== 'apk') {
      return json({ error: 'bad prefix' }, 400);
    }

    const contentType = req.headers.get('content-type') || 'application/octet-stream';
    const body = await req.arrayBuffer();
    if (body.byteLength === 0) return json({ error: 'empty file' }, 400);

    const key = `${prefix}/${name}`;
    if (prefix === 'wallpapers' && (await env.BUCKET.head(key))) {
      return json({ error: 'file already exists' }, 409);
    }

    await env.BUCKET.put(key, body, { httpMetadata: { contentType } });

    return json({ ok: true, url: `${PUBLIC_BASE}/${key}` });
  },
};
