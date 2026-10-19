const DEFAULT_SHARED = {
  autoDetectEnabled: true,
  blockedHosts: [
    "chatgpt.com","chat.openai.com","claude.ai","gemini.google.com",
    "aistudio.google.com","notebooklm.google.com","copilot.microsoft.com",
    "perplexity.ai","poe.com","grok.com","deepseek.com","meta.ai","you.com",
    "character.ai","chat.mistral.ai","kimi.com","qwen.ai","chat.qwen.ai",
    "phind.com","blackbox.ai","pi.ai","duck.ai","z.ai","wrtn.ai",
    "easemate.ai","easemate.net","easymate.ai"
  ],
  allowedHosts: [
    "google.com","naver.com","daum.net","kakao.com",
    "wikipedia.org","namu.wiki","youtube.com"
  ],
  revision: "default"
};

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "Content-Type, X-Admin-Key",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Cache-Control": "no-store"
};

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...CORS, "Content-Type": "application/json; charset=utf-8" }
  });
}

function validClassCode(value) {
  return /^\d{4,6}$/.test(String(value || "").trim());
}

function normalizeHost(value) {
  let out = String(value || "").trim().toLowerCase();
  out = out.replace(/^https?:\/\//, "").split("/")[0].split("?")[0].split("#")[0];
  out = out.replace(/^www\./, "").replace(/^\.+/, "");
  return out;
}

function normalizeHosts(values) {
  if (!Array.isArray(values)) return [];
  return [...new Set(values.map(normalizeHost).filter(Boolean))].sort();
}

async function sha256Hex(value) {
  const bytes = new TextEncoder().encode(String(value));
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map(b => b.toString(16).padStart(2, "0")).join("");
}

async function readBody(request) {
  try {
    return await request.json();
  } catch {
    return null;
  }
}

async function getClassConfig(env, classCode) {
  const value = await env.DATA.get("class:" + classCode, "json");
  return value || {
    enabled: true,
    resetEpoch: 0,
    adminHash: null,
    revision: new Date(0).toISOString()
  };
}

async function saveClassConfig(env, classCode, cfg) {
  await env.DATA.put("class:" + classCode, JSON.stringify(cfg));
}

async function getSharedPolicy(env) {
  const value = await env.DATA.get("shared:policy", "json");
  return value || DEFAULT_SHARED;
}

async function saveSharedPolicy(env, policy) {
  await env.DATA.put("shared:policy", JSON.stringify(policy));
}

async function authorizeClass(request, env, classCode) {
  const key = request.headers.get("X-Admin-Key") || "";
  if (key.length < 32) return false;
  const cfg = await getClassConfig(env, classCode);
  if (!cfg.adminHash) return false;
  return (await sha256Hex(key)) === cfg.adminHash;
}

async function listRegisteredDevices(env, classCode, resetEpoch) {
  const prefix = "device:" + classCode + ":";
  let cursor = undefined;
  const devices = [];

  do {
    const page = await env.DATA.list({ prefix, cursor, limit: 1000 });
    for (const item of page.keys) {
      const d = await env.DATA.get(item.name, "json");
      if (!d) continue;
      if (Number(d.registeredAt || 0) <= Number(resetEpoch || 0)) continue;
      devices.push(d);
    }
    cursor = page.list_complete ? undefined : page.cursor;
  } while (cursor);

  devices.sort((a, b) => Number(b.lastSeen || 0) - Number(a.lastSeen || 0));
  return devices;
}

async function handleHeartbeat(request, env) {
  const body = await readBody(request);
  if (!body || !validClassCode(body.classCode)) {
    return json({ error: "invalid_class" }, 400);
  }

  const deviceId = String(body.deviceId || "");
  if (!/^[a-f0-9]{8,64}$/i.test(deviceId)) {
    return json({ error: "invalid_device" }, 400);
  }

  const registeredAt = Number(body.registeredAt || 0);
  if (!Number.isFinite(registeredAt) || registeredAt <= 0) {
    return json({ error: "invalid_registered_at" }, 400);
  }

  const now = Date.now();
  const record = {
    deviceId,
    classCode: String(body.classCode),
    registeredAt,
    lastSeen: now,
    appVersion: String(body.appVersion || "").slice(0, 32),
    accessibilityEnabled: body.accessibilityEnabled === true
  };

  await env.DATA.put(
    "device:" + record.classCode + ":" + deviceId,
    JSON.stringify(record),
    { expirationTtl: 60 * 60 * 24 * 730 }
  );

  return json({ ok: true, serverTime: now });
}

async function handlePublicPolicy(env, classCode) {
  if (!validClassCode(classCode)) return json({ error: "invalid_class" }, 400);

  const [cfg, shared] = await Promise.all([
    getClassConfig(env, classCode),
    getSharedPolicy(env)
  ]);

  return json({
    enabled: cfg.enabled !== false,
    resetEpoch: Number(cfg.resetEpoch || 0),
    autoDetectEnabled: shared.autoDetectEnabled !== false,
    blockedHosts: normalizeHosts(shared.blockedHosts),
    allowedHosts: normalizeHosts(shared.allowedHosts),
    revision: String(Math.max(
      Date.parse(cfg.revision || 0) || 0,
      Date.parse(shared.revision || 0) || 0
    ))
  });
}

async function handleBootstrap(request, env) {
  const body = await readBody(request);
  if (!body || !validClassCode(body.classCode)) {
    return json({ error: "invalid_class" }, 400);
  }

  const adminKey = String(body.adminKey || "");
  if (adminKey.length < 32) return json({ error: "invalid_admin_key" }, 400);

  const classCode = String(body.classCode);
  const cfg = await getClassConfig(env, classCode);
  const hash = await sha256Hex(adminKey);

  if (cfg.adminHash && cfg.adminHash !== hash) {
    return json({ error: "class_already_claimed" }, 403);
  }

  cfg.adminHash = hash;
  cfg.enabled = cfg.enabled !== false;
  cfg.resetEpoch = Number(cfg.resetEpoch || 0);
  cfg.revision = new Date().toISOString();
  await saveClassConfig(env, classCode, cfg);

  return json({ ok: true, classCode });
}

async function handleAdminClassGet(request, env, classCode) {
  if (!validClassCode(classCode)) return json({ error: "invalid_class" }, 400);
  if (!(await authorizeClass(request, env, classCode))) {
    return json({ error: "unauthorized" }, 401);
  }

  const cfg = await getClassConfig(env, classCode);
  const devices = await listRegisteredDevices(env, classCode, cfg.resetEpoch);
  const now = Date.now();

  return json({
    classCode,
    enabled: cfg.enabled !== false,
    resetEpoch: Number(cfg.resetEpoch || 0),
    registeredCount: devices.length,
    active10mCount: devices.filter(d => now - Number(d.lastSeen || 0) <= 10 * 60 * 1000).length,
    accessibilityOnCount: devices.filter(d => d.accessibilityEnabled === true).length,
    devices: devices.map(d => ({
      deviceId: d.deviceId,
      lastSeen: Number(d.lastSeen || 0),
      registeredAt: Number(d.registeredAt || 0),
      appVersion: d.appVersion || "",
      accessibilityEnabled: d.accessibilityEnabled === true
    }))
  });
}

async function handleAdminClassState(request, env, classCode) {
  if (!(await authorizeClass(request, env, classCode))) {
    return json({ error: "unauthorized" }, 401);
  }

  const body = await readBody(request);
  if (!body || typeof body.enabled !== "boolean") {
    return json({ error: "invalid_enabled" }, 400);
  }

  const cfg = await getClassConfig(env, classCode);
  cfg.enabled = body.enabled;
  cfg.revision = new Date().toISOString();
  await saveClassConfig(env, classCode, cfg);

  return json({ ok: true, enabled: cfg.enabled });
}

async function handleAdminClassReset(request, env, classCode) {
  if (!(await authorizeClass(request, env, classCode))) {
    return json({ error: "unauthorized" }, 401);
  }

  const cfg = await getClassConfig(env, classCode);
  cfg.resetEpoch = Date.now();
  cfg.revision = new Date().toISOString();
  await saveClassConfig(env, classCode, cfg);

  return json({ ok: true, resetEpoch: cfg.resetEpoch });
}

async function handleAdminSharedGet(request, env, classCode) {
  if (!(await authorizeClass(request, env, classCode))) {
    return json({ error: "unauthorized" }, 401);
  }
  return json(await getSharedPolicy(env));
}

async function handleAdminSharedPost(request, env, classCode) {
  if (!(await authorizeClass(request, env, classCode))) {
    return json({ error: "unauthorized" }, 401);
  }

  const body = await readBody(request);
  if (!body) return json({ error: "invalid_body" }, 400);

  const policy = {
    autoDetectEnabled: body.autoDetectEnabled !== false,
    blockedHosts: normalizeHosts(body.blockedHosts),
    allowedHosts: normalizeHosts(body.allowedHosts),
    revision: new Date().toISOString()
  };
  await saveSharedPolicy(env, policy);
  return json({ ok: true, ...policy });
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return new Response(null, { headers: CORS });

    if (!env.DATA) {
      return json({ error: "kv_binding_missing", binding: "DATA" }, 503);
    }

    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";

    if (path === "/health") {
      return json({ ok: true, service: "ai-blocker-management" });
    }

    if (path === "/api/heartbeat" && request.method === "POST") {
      return handleHeartbeat(request, env);
    }

    let m = path.match(/^\/api\/policy\/(\d{4,6})$/);
    if (m && request.method === "GET") {
      return handlePublicPolicy(env, m[1]);
    }

    if (path === "/api/admin/bootstrap" && request.method === "POST") {
      return handleBootstrap(request, env);
    }

    m = path.match(/^\/api\/admin\/class\/(\d{4,6})$/);
    if (m && request.method === "GET") {
      return handleAdminClassGet(request, env, m[1]);
    }

    m = path.match(/^\/api\/admin\/class\/(\d{4,6})\/state$/);
    if (m && request.method === "POST") {
      return handleAdminClassState(request, env, m[1]);
    }

    m = path.match(/^\/api\/admin\/class\/(\d{4,6})\/reset$/);
    if (m && request.method === "POST") {
      return handleAdminClassReset(request, env, m[1]);
    }

    m = path.match(/^\/api\/admin\/shared\/(\d{4,6})$/);
    if (m && request.method === "GET") {
      return handleAdminSharedGet(request, env, m[1]);
    }
    if (m && request.method === "POST") {
      return handleAdminSharedPost(request, env, m[1]);
    }

    return json({ error: "not_found" }, 404);
  }
};
