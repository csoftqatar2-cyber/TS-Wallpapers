import { createClient } from 'jsr:@supabase/supabase-js@2'

// The single admin account. Only this user id may upload.
const ADMIN_ID = '5b8e1336-ce54-4dd9-bd23-243158c178fe'

const cors = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers':
    'authorization, x-client-info, apikey, content-type, x-file-name, x-file-type, x-is-global, x-hardware-id',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...cors, 'Content-Type': 'application/json' },
  })
}

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: cors })
  if (req.method !== 'POST') return json({ error: 'method not allowed' }, 405)

  const url = Deno.env.get('SUPABASE_URL')!
  const anonKey = Deno.env.get('SUPABASE_ANON_KEY')!
  const serviceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!

  try {
    // 1) Validate the caller's token via GoTrue and require the admin identity.
    const token = (req.headers.get('Authorization') || '').replace('Bearer ', '').trim()
    if (!token) return json({ error: 'no token' }, 401)

    const authClient = createClient(url, anonKey)
    const { data: userData, error: userErr } = await authClient.auth.getUser(token)
    if (userErr || !userData?.user) return json({ error: 'invalid session' }, 401)
    if (userData.user.id !== ADMIN_ID) return json({ error: 'not authorized' }, 403)

    // 2) Read the file + metadata. x-file-name is URI-encoded for safe header
    //    transport (supports Arabic names); decode it back to the real key.
    const rawHeaderName = req.headers.get('x-file-name')
    if (!rawHeaderName) return json({ error: 'missing file name' }, 400)
    let clientName = rawHeaderName
    try { clientName = decodeURIComponent(rawHeaderName) } catch (_e) { /* keep as-is */ }

    const fileType = req.headers.get('x-file-type') || 'image'
    const isGlobal = req.headers.get('x-is-global') === 'true'
    const hardwareId = req.headers.get('x-hardware-id') || null
    const contentType = req.headers.get('content-type') || 'application/octet-stream'
    const bytes = new Uint8Array(await req.arrayBuffer())
    if (bytes.length === 0) return json({ error: 'empty file' }, 400)

    // Give every upload a GUARANTEED-UNIQUE storage key. Two customers very often
    // upload files with the same name ("1.jpg", "IMG_0001.jpg"...). Storing them under
    // that raw name used to overwrite one customer's private wallpaper with another's
    // at the same URL, so a private image leaked onto the wrong car. A UUID prefix makes
    // collisions impossible regardless of what the client sends (even a stale page).
    const dot = clientName.lastIndexOf('.')
    const ext = dot >= 0 ? clientName.slice(dot) : ''
    const fileName = `${crypto.randomUUID()}${ext}`

    // 3) Upload with the service role (bypasses storage RLS) + record the row.
    const admin = createClient(url, serviceKey)

    // upsert:false — never silently overwrite an existing object. With the UUID name a
    // collision is effectively impossible; if one ever happened we fail loudly instead
    // of leaking one customer's image onto another's device.
    const { error: upErr } = await admin.storage
      .from('wallpapers')
      .upload(fileName, bytes, { contentType, upsert: false })
    if (upErr) return json({ error: 'storage: ' + upErr.message }, 500)

    const publicUrl = `${url}/storage/v1/object/public/wallpapers/${encodeURIComponent(fileName)}`
    // Plain insert (not upsert-on-url): each upload is its own distinct row, so nothing
    // can be reassigned to a different device by re-using a URL.
    const { error: dbErr } = await admin
      .from('wallpapers')
      .insert({ url: publicUrl, type: fileType, is_global: isGlobal, hardware_id: hardwareId })
    if (dbErr) return json({ error: 'db: ' + dbErr.message }, 500)

    return json({ ok: true, url: publicUrl })
  } catch (e) {
    return json({ error: String((e && (e as Error).message) || e) }, 500)
  }
})
