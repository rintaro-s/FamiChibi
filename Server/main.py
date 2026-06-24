import asyncio
import atexit
import json
import os
import random
import secrets
import shutil
import socket
import threading
import uuid
import io
import qrcode
from datetime import datetime, timedelta
from pathlib import Path
from typing import Dict, List, Optional, Any

import aiofiles
import httpx
from fastapi import (
    FastAPI, WebSocket, WebSocketDisconnect, Form, Request,
    UploadFile, File, HTTPException
)
from fastapi.responses import HTMLResponse, JSONResponse, Response, StreamingResponse
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware

app = FastAPI(title="FamiChibi Server", version="4.0.0")
BASE_DIR = Path(__file__).resolve().parent
WORKSPACE_ROOT = BASE_DIR.parent
DATA_DIR = BASE_DIR / "data"
DATA_DIR.mkdir(exist_ok=True)
STATE_FILE = DATA_DIR / "servers.json"
ADMIN_TOKEN_FILE = DATA_DIR / "admin_token.txt"

if ADMIN_TOKEN_FILE.exists():
    ADMIN_TOKEN = ADMIN_TOKEN_FILE.read_text().strip()
else:
    ADMIN_TOKEN = secrets.token_urlsafe(16)
    ADMIN_TOKEN_FILE.write_text(ADMIN_TOKEN)
print(f"[Admin] Token: {ADMIN_TOKEN}")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ---------------------------------------------------------------------------
# Data models
# ---------------------------------------------------------------------------

DEFAULT_ROLES = {
    "owner": {"permissions": ["*"]},
    "admin": {"permissions": ["manage_server", "manage_channels", "manage_members", "manage_bots", "manage_notes", "manage_invites", "send_messages", "use_voice", "view_channels"]},
    "moderator": {"permissions": ["manage_members", "manage_notes", "send_messages", "use_voice", "view_channels"]},
    "member": {"permissions": ["send_messages", "use_voice", "manage_notes", "view_channels"]},
    "guest": {"permissions": ["view_channels"]},
}

HONORIFIC_SUFFIXES = ("ちゃん", "くん", "さん", "様", "殿", "先生", "先輩", "後輩", "君")

def format_name(name: str) -> str:
    if not name:
        return "匿名"
    name = name.strip()
    if name.endswith(HONORIFIC_SUFFIXES):
        return name
    return f"{name}さん"

def make_default_bot(name: str = "ファミちび", personality: str = "やさしい") -> dict:
    return {
        "id": str(uuid.uuid4()),
        "name": name,
        "personality": personality,
        "mood": 0,
        "voice_enabled": True,
        "proactive_enabled": True,
        "model": "",
        "temperature": 0.7,
        "system_prompt": "",
        "channels": [],
    }

def make_default_server(server_id: str = "default", name: str = "Default", owner_id: str = "") -> dict:
    default_text = make_text_channel("一般")
    default_text["ai_enabled"] = True
    return {
        "id": server_id,
        "name": name,
        "password": "",
        "icon": "",
        "welcome_message": "",
        "owner_id": owner_id,
        "created_at": datetime.utcnow().isoformat(),
        "roles": dict(DEFAULT_ROLES),
        "members": {},
        "invites": [],
        "bots": [make_default_bot()],
        "knowledge_base": [],
        "rooms": {default_text["id"]: default_text},
    }

def make_text_channel(name: str, password: str = "", visibility: str = "public") -> dict:
    return {
        "id": secrets.token_hex(4),
        "name": name,
        "type": "text",
        "password": password,
        "visibility": visibility,
        "allowed_roles": ["member", "moderator", "admin", "owner"],
        "allowed_users": [],
        "ai_enabled": True,
        "created_at": datetime.utcnow().isoformat(),
        "messages": [],
        "notes": [],
        "tasks": [],
        "events": [],
        "photos": [],
        "users": {},
    }

def make_voice_channel(name: str, password: str = "", visibility: str = "public") -> dict:
    return {
        "id": secrets.token_hex(4),
        "name": name,
        "type": "voice",
        "password": password,
        "visibility": visibility,
        "allowed_roles": ["member", "moderator", "admin", "owner"],
        "allowed_users": [],
        "created_at": datetime.utcnow().isoformat(),
        "users": {},
    }

def make_invite(code: str, created_by: str, channel_id: Optional[str] = None, max_uses: int = 0, expires_hours: int = 0) -> dict:
    expires_at = None
    if expires_hours > 0:
        expires_at = (datetime.utcnow() + timedelta(hours=expires_hours)).isoformat()
    return {
        "code": code,
        "channel_id": channel_id,
        "created_by": created_by,
        "uses": 0,
        "max_uses": max_uses,
        "expires_at": expires_at,
        "created_at": datetime.utcnow().isoformat(),
    }

servers: Dict[str, dict] = {}

# ---------------------------------------------------------------------------
# Persistence
# ---------------------------------------------------------------------------

async def save_state():
    try:
        tmp = STATE_FILE.with_suffix(".tmp")
        async with aiofiles.open(tmp, "w", encoding="utf-8") as f:
            await f.write(json.dumps(servers, ensure_ascii=False, default=str))
        tmp.replace(STATE_FILE)
        print("[State] Saved")
    except Exception as e:
        print(f"[State] Save failed: {e}")

def load_state():
    global servers
    if STATE_FILE.exists():
        try:
            with open(STATE_FILE, "r", encoding="utf-8") as f:
                servers = json.load(f)
            for sid, s in servers.items():
                s.setdefault("owner_id", "")
                s.setdefault("roles", dict(DEFAULT_ROLES))
                s.setdefault("members", {})
                s.setdefault("invites", [])
                s.setdefault("bots", s.pop("agents", [make_default_bot()]) if "agents" in s else [make_default_bot()])
                s.setdefault("knowledge_base", [])
                s.setdefault("icon", "")
                s.setdefault("welcome_message", "")
                for rid, r in s.get("rooms", {}).items():
                    r.setdefault("type", "text")
                    r.setdefault("ai_enabled", True)
                    r.setdefault("agents", [])
                    r.setdefault("visibility", "public")
                    r.setdefault("allowed_roles", ["member", "moderator", "admin", "owner"])
                    r.setdefault("allowed_users", [])
            print("[State] Loaded from disk")
        except Exception as e:
            print(f"[State] Load failed: {e}")
            backup = STATE_FILE.with_suffix(f".bak.{datetime.utcnow().strftime('%Y%m%d%H%M%S')}")
            try: shutil.copy2(STATE_FILE, backup)
            except Exception: pass
            servers = {}
    if not servers:
        servers["default"] = make_default_server("default", "Default")
        asyncio.get_event_loop().run_until_complete(save_state())

load_state()
def _atexit_save():
    try:
        loop = asyncio.get_event_loop()
        if loop.is_running(): loop.create_task(save_state())
        else: loop.run_until_complete(save_state())
    except RuntimeError:
        loop = asyncio.new_event_loop()
        loop.run_until_complete(save_state())
        loop.close()
atexit.register(_atexit_save)

async def autosave():
    while True:
        await asyncio.sleep(60)
        await save_state()

@app.on_event("startup")
async def on_startup():
    asyncio.create_task(autosave())
    src_vrm = WORKSPACE_ROOT / "AvatarSample_M.vrm"
    dst_dir = BASE_DIR / "static" / "avatars"
    dst_dir.mkdir(parents=True, exist_ok=True)
    dst_vrm = dst_dir / "AvatarSample_M.vrm"
    if src_vrm.exists() and not dst_vrm.exists():
        shutil.copy2(src_vrm, dst_vrm)

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def get_server(server_id: str) -> dict:
    if server_id not in servers:
        raise HTTPException(status_code=404, detail="Server not found")
    return servers[server_id]

def get_channel(server: dict, channel_id: str) -> dict:
    room = server.get("rooms", {}).get(channel_id)
    if not room:
        raise HTTPException(status_code=404, detail="Channel not found")
    return room

def require_local(request: Request):
    client = request.client.host if request.client else ""
    forwarded = request.headers.get("x-forwarded-for", "")
    if forwarded and client in ("127.0.0.1", "::1", "localhost"):
        client = forwarded.split(",")[0].strip()
    if client in ("127.0.0.1", "::1", "localhost"): return
    if client.startswith("10.") or client.startswith("192."): return
    if client.startswith("172."):
        try:
            second = int(client.split(".")[1])
            if 16 <= second <= 31: return
        except Exception: pass
    raise HTTPException(status_code=403, detail="Admin access restricted to local network")

def require_admin_token(request: Request):
    token = request.query_params.get("token", "") or request.headers.get("x-admin-token", "")
    if token != ADMIN_TOKEN:
        raise HTTPException(status_code=403, detail="Invalid admin token")

def has_permission(server: dict, user_id: str, permission: str) -> bool:
    member = server.get("members", {}).get(user_id)
    if not member: return False
    role = member.get("role", "guest")
    perms = server.get("roles", {}).get(role, {}).get("permissions", [])
    return "*" in perms or permission in perms

def is_owner(server: dict, user_id: str) -> bool:
    return server.get("owner_id") == user_id

def can_view_channel(server: dict, user_id: str, channel: dict) -> bool:
    if is_owner(server, user_id): return True
    if channel.get("visibility") == "public":
        return user_id in server.get("members", {})
    member = server.get("members", {}).get(user_id)
    if not member: return False
    role = member.get("role", "guest")
    if role in channel.get("allowed_roles", []): return True
    if user_id in channel.get("allowed_users", []): return True
    return has_permission(server, user_id, "manage_channels")

def require_member(server: dict, user_id: str):
    if user_id not in server.get("members", {}):
        raise HTTPException(status_code=403, detail="Not a member of this server")

def require_permission(server: dict, user_id: str, permission: str):
    if not has_permission(server, user_id, permission):
        raise HTTPException(status_code=403, detail=f"Missing permission: {permission}")

MAX_MESSAGE_LENGTH = 2000
MAX_OLLAMA_PROMPT_LENGTH = 5000
MAX_FILE_SIZE = 10 * 1024 * 1024

# ---------------------------------------------------------------------------
# Ollama (SSRF-safe)
# ---------------------------------------------------------------------------

PRIVATE_IP_PREFIXES = ("10.", "192.168.", "172.16.", "172.17.", "172.18.", "172.19.", "172.20.", "172.21.", "172.22.", "172.23.", "172.24.", "172.25.", "172.26.", "172.27.", "172.28.", "172.29.", "172.30.", "172.31.", "127.", "0.", "localhost", "::1")

def validate_ollama_url(url: str) -> bool:
    if not url: return True
    url_lower = url.lower()
    if not url_lower.startswith(("http://", "https://")): return False
    for prefix in PRIVATE_IP_PREFIXES:
        if prefix in url_lower: return False
    return True

async def ollama_generate(server: dict, prompt: str) -> Optional[str]:
    url = server.get("ollama_url", "").rstrip("/")
    model = server.get("ollama_model", "llama3")
    if not url: return None
    if not validate_ollama_url(url):
        print("[Ollama] SSRF blocked")
        return None
    if len(prompt) > MAX_OLLAMA_PROMPT_LENGTH: prompt = prompt[:MAX_OLLAMA_PROMPT_LENGTH]
    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            res = await client.post(f"{url}/api/generate", json={"model": model, "prompt": prompt, "stream": False})
            return res.json().get("response", "")
    except Exception as e:
        print(f"[Ollama] error: {e}")
        return None

async def generate_agent_reply(server: dict, bot: dict, channel: dict, message: str, sender: str) -> Optional[str]:
    notes = channel.get("notes", [])
    tasks = [t for t in channel.get("tasks", []) if not t.get("done")]
    events = channel.get("events", [])
    recent_msgs = channel.get("messages", [])[-10:]
    knowledge = server.get("knowledge_base", [])
    prompt_parts = [f"あなたは{bot['name']}です。性格: {bot.get('personality', 'やさしい')}。", "家族の最近のメッセージとメモ:"]
    for m in recent_msgs: prompt_parts.append(f"- {m.get('sender', '')}: {m.get('content', '')}")
    if notes: prompt_parts.append("メモ:"); [prompt_parts.append(f"- [{n.get('category', '一般')}] {n.get('content', '')}") for n in notes[-5:]]
    if tasks: prompt_parts.append("お手伝い:"); [prompt_parts.append(f"- {t.get('title', '')}") for t in tasks[:3]]
    if events: prompt_parts.append("予定:"); [prompt_parts.append(f"- {e.get('title', '')} ({e.get('event_at', '')})") for e in events[:3]]
    relevant = [k for k in knowledge if any(w in message for w in k.get("title", "").split())][:3]
    if relevant: prompt_parts.append("参考知識:"); [prompt_parts.append(f"- {k.get('title', '')}: {k.get('content', '')[:200]}") for k in relevant]
    prompt_parts.append(f"{format_name(sender)}のメッセージ:「{message[:500]}」")
    prompt_parts.append("やさしく簡潔に返信してください。")
    prompt = "\n".join(prompt_parts)
    ollama_reply = await ollama_generate(server, prompt)
    if ollama_reply: return ollama_reply.strip()
    replies = [f"{format_name(sender)}、がんばって！", "それはすごいね！応援してるよ！", f"ふふっ、{format_name(sender)}らしいね。", "大丈夫、きっとうまくいくよ！", f"{format_name(sender)}のこと、信じてる！"]
    personality = bot.get("personality", "")
    if "ツンデレ" in personality: replies = [f"べ、別に{sender}のためじゃないんだから！", "ちょっとがんばりすぎよ...", f"まあ、{sender}ならできるんじゃない？"]
    elif "おしとやか" in personality: replies = [f"{format_name(sender)}、応援しておりますわ。", f"素敵ですわね、{format_name(sender)}", "ご無理はなさらないでくださいませ"]
    elif "元気" in personality: replies = [f"{sender}！超がんばれー！！", f"イェーイ！{sender}最高！！", "おおお！それはアツいね！"]
    return random.choice(replies)

async def generate_photo_comment(server: dict, bot: dict, uploader: str) -> Optional[str]:
    prompt = f"{format_name(uploader)}が写真を共有しました。写真へのやさしいコメントを1行で。"
    return await ollama_generate(server, prompt) or f"{format_name(uploader)}、素敵な写真をありがとう！"

# ---------------------------------------------------------------------------
# VOICEVOX
# ---------------------------------------------------------------------------

VOICEVOX_BASE = os.environ.get("VOICEVOX_URL", "http://localhost:50021")

@app.post("/voicevox/synthesize")
async def voicevox_synthesize(text: str = Form(...), speaker: int = Form(0)):
    if len(text) > 500: return JSONResponse({"error": "Text too long"}, status_code=400)
    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            aq = await client.post(f"{VOICEVOX_BASE}/audio_query", params={"text": text, "speaker": speaker})
            query = aq.json()
            synth = await client.post(f"{VOICEVOX_BASE}/synthesis", params={"speaker": speaker}, content=json.dumps(query), headers={"Content-Type": "application/json"})
            return Response(content=synth.content, media_type="audio/wav")
    except Exception as e:
        return JSONResponse({"error": str(e)}, status_code=503)

# ---------------------------------------------------------------------------
# Static / templates
# ---------------------------------------------------------------------------

static_dir = BASE_DIR / "static"
if not static_dir.exists(): static_dir = WORKSPACE_ROOT / "static"
template_dir = BASE_DIR / "templates"
if not template_dir.exists(): template_dir = WORKSPACE_ROOT / "templates"

os.makedirs(static_dir, exist_ok=True)
os.makedirs(static_dir / "avatars", exist_ok=True)
os.makedirs(static_dir / "photos", exist_ok=True)
os.makedirs(static_dir / "web", exist_ok=True)

app.mount("/static", StaticFiles(directory=str(static_dir)), name="static")

_web_html = ""
_admin_html = ""
try:
    with open(static_dir / "web" / "index.html", "r", encoding="utf-8") as f: _web_html = f.read()
except Exception: pass
try:
    with open(template_dir / "admin.html", "r", encoding="utf-8") as f: _admin_html = f.read()
except Exception: pass

# ---------------------------------------------------------------------------
# Admin
# ---------------------------------------------------------------------------

@app.get("/admin", response_class=HTMLResponse)
async def admin_ui(request: Request):
    require_local(request)
    token = request.query_params.get("token", "")
    if token != ADMIN_TOKEN:
        return HTMLResponse("<h1>Admin Access Denied</h1><p>Invalid or missing token.</p>", status_code=403)
    return _admin_html if _admin_html else "<h1>Admin panel not found</h1>"

@app.get("/admin/api/servers")
async def admin_list_servers(request: Request):
    require_local(request); require_admin_token(request)
    return [{"id": sid, "name": s["name"], "room_count": len(s.get("rooms", {})), "member_count": len(s.get("members", {})), "has_password": bool(s.get("password")), "ollama_url": s.get("ollama_url", ""), "ollama_model": s.get("ollama_model", ""), "voicevox_speaker": s.get("voicevox_speaker", 0)} for sid, s in servers.items()]

@app.post("/admin/api/servers")
async def admin_create_server(request: Request, name: str = Form(...), password: Optional[str] = Form("")):
    require_local(request); require_admin_token(request)
    sid = "srv_" + secrets.token_hex(4)
    servers[sid] = make_default_server(sid, name)
    servers[sid]["password"] = password
    await save_state()
    return {"id": sid, "name": name}

@app.delete("/admin/api/servers/{sid}")
async def admin_delete_server(sid: str, request: Request):
    require_local(request); require_admin_token(request)
    s = get_server(sid)
    for rid, room in s.get("rooms", {}).items():
        for photo in room.get("photos", []):
            ppath = static_dir / "photos" / f"{photo.get('id', '')}.jpg"
            if ppath.exists(): ppath.unlink()
    for key in list(manager.active.keys()):
        if key.startswith(f"{sid}:"):
            for conn in manager.active.pop(key, []):
                try: await conn["ws"].close()
                except Exception: pass
    del servers[sid]
    await save_state()
    return {"deleted": sid}

@app.post("/admin/api/servers/{sid}/config")
async def admin_update_config(sid: str, request: Request, name: Optional[str] = Form(None), password: Optional[str] = Form(None), ollama_url: Optional[str] = Form(None), ollama_model: Optional[str] = Form(None), voicevox_speaker: Optional[int] = Form(None)):
    require_local(request); require_admin_token(request)
    s = get_server(sid)
    if name is not None: s["name"] = name
    if password is not None and password != "__NOCHANGE__": s["password"] = password
    if ollama_url is not None: s["ollama_url"] = ollama_url
    if ollama_model is not None: s["ollama_model"] = ollama_model
    if voicevox_speaker is not None: s["voicevox_speaker"] = voicevox_speaker
    await save_state()
    return {"ok": True}

@app.get("/admin/api/servers/{sid}/rooms")
async def admin_list_rooms(sid: str, request: Request):
    require_local(request); require_admin_token(request)
    s = get_server(sid)
    return [{"id": rid, "name": r["name"], "type": r.get("type", "text"), "user_count": len(r.get("users", {})), "has_password": bool(r.get("password"))} for rid, r in s.get("rooms", {}).items()]

# ---------------------------------------------------------------------------
# Discovery
# ---------------------------------------------------------------------------

@app.get("/discover")
async def discover():
    import sys
    port = 8000
    for arg in sys.argv:
        if arg.startswith("--port="):
            try: port = int(arg.split("=", 1)[1])
            except Exception: pass
    return {"name": "FamiChibi Server", "version": "4.0.0", "port": port, "features": ["rooms", "password", "avatars", "websocket", "notebook", "tasks", "events", "photos", "whisper", "nudge", "reactions", "agent_memory", "ollama", "voicevox", "admin_panel", "voice_channels", "ai_bots", "permissions", "knowledge_base", "invites"]}

# ---------------------------------------------------------------------------
# Public server list (only servers without password, or minimal info)
# ---------------------------------------------------------------------------

@app.get("/servers")
async def list_servers():
    return [{"id": sid, "name": s["name"], "has_password": bool(s.get("password")), "icon": s.get("icon", ""), "member_count": len(s.get("members", {}))} for sid, s in servers.items()]

@app.post("/servers")
async def create_new_server(name: str = Form(...), password: Optional[str] = Form(""), owner_id: Optional[str] = Form(None), owner_name: Optional[str] = Form("オーナー")):
    sid = "srv_" + secrets.token_hex(4)
    uid = owner_id or str(uuid.uuid4())
    servers[sid] = make_default_server(sid, name)
    servers[sid]["password"] = password
    servers[sid]["owner_id"] = uid
    servers[sid]["members"][uid] = {"user_id": uid, "user_name": owner_name, "role": "owner", "joined_at": datetime.utcnow().isoformat()}
    await save_state()
    return {"id": sid, "name": name, "owner_id": uid}


# ---------------------------------------------------------------------------
# Server membership / auth helpers
# ---------------------------------------------------------------------------

async def identify_user(request: Request) -> tuple:
    """Return (user_id, user_name) from query/header/form, with fallback."""
    uid = request.query_params.get("user_id") or request.headers.get("x-user-id")
    uname = request.query_params.get("user_name") or request.headers.get("x-user-name")
    if not uid or not uname:
        try:
            form = await request.form()
            uid = uid or form.get("user_id")
            uname = uname or form.get("user_name")
        except Exception:
            pass
    if not uid: uid = str(uuid.uuid4())
    if not uname: uname = "匿名"
    return uid, uname

async def server_info(server: dict, user_id: str) -> dict:
    member = server.get("members", {}).get(user_id)
    role = member.get("role") if member else None
    return {
        "id": server["id"],
        "name": server["name"],
        "icon": server.get("icon", ""),
        "welcome_message": server.get("welcome_message", ""),
        "has_password": bool(server.get("password")),
        "member_count": len(server.get("members", {})),
        "my_role": role,
        "my_user_id": user_id,
        "permissions": server.get("roles", {}).get(role, {}).get("permissions", []) if role else [],
        "owner_id": server.get("owner_id", ""),
        "channels": [
            await channel_summary(server, r, user_id)
            for rid, r in server.get("rooms", {}).items() if can_view_channel(server, user_id, r)
        ],
        "roles": list(server.get("roles", {}).keys()),
    }

@app.get("/s/{sid}")
async def get_server_info(sid: str, request: Request):
    s = get_server(sid)
    uid, _ = await identify_user(request)
    if uid not in s.get("members", {}):
        return JSONResponse({
            "id": sid, "name": s["name"], "icon": s.get("icon", ""),
            "welcome_message": s.get("welcome_message", ""),
            "has_password": bool(s.get("password")),
            "member_count": len(s.get("members", {})),
            "my_role": None, "my_user_id": None, "permissions": [], "owner_id": s.get("owner_id", ""),
            "channels": [], "roles": list(s.get("roles", {}).keys())
        }, status_code=200)
    return await server_info(s, uid)

@app.post("/s/{sid}/join")
async def join_server(sid: str, request: Request, password: Optional[str] = Form(""), user_id: Optional[str] = Form(None), user_name: Optional[str] = Form(None), invite_code: Optional[str] = Form(None)):
    s = get_server(sid)
    uid, uname = await identify_user(request)
    if user_id: uid = user_id
    if user_name: uname = user_name
    if uid in s.get("members", {}):
        return {"joined": True, "user_id": uid, "role": s["members"][uid].get("role", "guest")}
    invite_valid = False
    if invite_code:
        for inv in s.get("invites", []):
            if inv["code"] == invite_code:
                if inv.get("expires_at") and datetime.fromisoformat(inv["expires_at"]) < datetime.utcnow(): continue
                if inv.get("max_uses") and inv.get("uses", 0) >= inv["max_uses"]: continue
                inv["uses"] = inv.get("uses", 0) + 1
                invite_valid = True
                break
    if s.get("password") and s["password"] != password and not invite_valid:
        return JSONResponse({"error": "Invalid password or invite"}, status_code=403)
    role = "member" if invite_valid else ("member" if not s.get("password") else "guest")
    if not s.get("members"): role = "owner"  # first member becomes owner if somehow empty
    s["members"][uid] = {"user_id": uid, "user_name": uname, "role": role, "joined_at": datetime.utcnow().isoformat()}
    await save_state()
    return {"joined": True, "user_id": uid, "role": role}

@app.post("/s/{sid}/leave")
async def leave_server(sid: str, request: Request, user_id: Optional[str] = Form(None)):
    s = get_server(sid)
    uid, _ = await identify_user(request)
    if user_id: uid = user_id
    if is_owner(s, uid):
        return JSONResponse({"error": "Owner cannot leave server"}, status_code=400)
    s.get("members", {}).pop(uid, None)
    for rid, room in s.get("rooms", {}).items():
        room.get("users", {}).pop(uid, None)
    await save_state()
    return {"left": True}

@app.put("/s/{sid}")
async def update_server(sid: str, request: Request, name: Optional[str] = Form(None), icon: Optional[str] = Form(None), welcome_message: Optional[str] = Form(None), password: Optional[str] = Form(None)):
    s = get_server(sid)
    uid, _ = await identify_user(request)
    require_permission(s, uid, "manage_server")
    if name is not None: s["name"] = name
    if icon is not None: s["icon"] = icon
    if welcome_message is not None: s["welcome_message"] = welcome_message
    if password is not None: s["password"] = password
    await save_state()
    return {"ok": True}

@app.delete("/s/{sid}")
async def delete_server(sid: str, request: Request):
    s = get_server(sid)
    uid, _ = await identify_user(request)
    if not is_owner(s, uid):
        require_permission(s, uid, "manage_server")
    del servers[sid]
    await save_state()
    return {"deleted": sid}

# ---------------------------------------------------------------------------
# Members
# ---------------------------------------------------------------------------

@app.get("/s/{sid}/members")
async def list_members(sid: str, request: Request):
    s = get_server(sid)
    uid, _ = await identify_user(request)
    require_member(s, uid)
    return [{"user_id": m["user_id"], "user_name": m.get("user_name", ""), "role": m.get("role", "guest"), "joined_at": m.get("joined_at", "")} for m in s.get("members", {}).values()]

@app.put("/s/{sid}/members/{target_uid}")
async def update_member(sid: str, target_uid: str, request: Request, role: Optional[str] = Form(None), user_name: Optional[str] = Form(None)):
    s = get_server(sid)
    uid, _ = await identify_user(request)
    require_member(s, uid)
    if target_uid == s.get("owner_id"):
        return JSONResponse({"error": "Cannot modify owner"}, status_code=403)
    if not has_permission(s, uid, "manage_members") and uid != target_uid:
        return JSONResponse({"error": "Forbidden"}, status_code=403)
    member = s.get("members", {}).get(target_uid)
    if not member: raise HTTPException(status_code=404, detail="Member not found")
    if user_name is not None:
        if uid != target_uid and not has_permission(s, uid, "manage_members"):
            return JSONResponse({"error": "Cannot change others' names"}, status_code=403)
        member["user_name"] = user_name
    if role is not None:
        if not has_permission(s, uid, "manage_members"):
            return JSONResponse({"error": "Missing permission"}, status_code=403)
        member["role"] = role
    await save_state()
    return {"ok": True}

@app.delete("/s/{sid}/members/{target_uid}")
async def kick_member(sid: str, target_uid: str, request: Request):
    s = get_server(sid)
    uid, _ = await identify_user(request)
    if target_uid == s.get("owner_id"):
        return JSONResponse({"error": "Cannot kick owner"}, status_code=403)
    if target_uid == uid:
        return await leave_server(sid, request)
    require_permission(s, uid, "manage_members")
    s.get("members", {}).pop(target_uid, None)
    for rid, room in s.get("rooms", {}).items():
        room.get("users", {}).pop(target_uid, None)
    await save_state()
    return {"kicked": target_uid}

# ---------------------------------------------------------------------------
# Roles
# ---------------------------------------------------------------------------

@app.get("/s/{sid}/roles")
async def list_roles(sid: str, request: Request):
    s = get_server(sid)
    uid, _ = await identify_user(request)
    require_member(s, uid)
    return s.get("roles", {})

@app.put("/s/{sid}/roles/{role_name}")
async def update_role(sid: str, role_name: str, request: Request, permissions: str = Form(...)):
    s = get_server(sid)
    uid, _ = await identify_user(request)
    require_permission(s, uid, "manage_server")
    if role_name in ("owner",):
        return JSONResponse({"error": "Cannot edit owner role"}, status_code=400)
    perms = [p.strip() for p in permissions.split(",") if p.strip()]
    s.setdefault("roles", {})[role_name] = {"permissions": perms}
    await save_state()
    return {"ok": True}

@app.post("/s/{sid}/roles")
async def create_role(sid: str, request: Request, role_name: str = Form(...), permissions: str = Form(...)):
    s = get_server(sid)
    uid, _ = await identify_user(request)
    require_permission(s, uid, "manage_server")
    if role_name in s.get("roles", {}):
        return JSONResponse({"error": "Role exists"}, status_code=409)
    perms = [p.strip() for p in permissions.split(",") if p.strip()]
    s.setdefault("roles", {})[role_name] = {"permissions": perms}
    await save_state()
    return {"role": role_name}

# ---------------------------------------------------------------------------
# Invites
# ---------------------------------------------------------------------------

@app.get("/s/{sid}/invites")
async def list_invites(sid: str, request: Request):
    s = get_server(sid)
    uid, _ = await identify_user(request)
    require_permission(s, uid, "manage_invites")
    now = datetime.utcnow()
    return [{"code": i["code"], "channel_id": i.get("channel_id"), "uses": i.get("uses", 0), "max_uses": i.get("max_uses", 0), "expires_at": i.get("expires_at"), "created_by": i.get("created_by", "")} for i in s.get("invites", []) if (not i.get("expires_at") or datetime.fromisoformat(i["expires_at"]) >= now)]

@app.post("/s/{sid}/invites")
async def create_server_invite(sid: str, request: Request, channel_id: Optional[str] = Form(None), max_uses: int = Form(0), expires_hours: int = Form(0)):
    s = get_server(sid)
    uid, _ = await identify_user(request)
    if channel_id:
        room = get_channel(s, channel_id)
        require_permission(s, uid, "manage_channels")
    else:
        require_permission(s, uid, "manage_invites")
    code = secrets.token_urlsafe(6)
    s.setdefault("invites", []).append(make_invite(code, uid, channel_id, max_uses, expires_hours))
    await save_state()
    return {"code": code}

@app.post("/invites/{code}/join")
async def join_with_invite(code: str, request: Request):
    uid, user_name = await identify_user(request)
    for sid, s in servers.items():
        for inv in s.get("invites", []):
            if inv["code"] != code: continue
            if inv.get("expires_at") and datetime.fromisoformat(inv["expires_at"]) < datetime.utcnow():
                continue
            if inv.get("max_uses") and inv.get("uses", 0) >= inv["max_uses"]:
                continue
            inv["uses"] = inv.get("uses", 0) + 1
            role = "member"
            channel_id = inv.get("channel_id")
            if channel_id:
                room = get_channel(s, channel_id)
                if uid not in s.get("members", {}):
                    s["members"][uid] = {"user_id": uid, "user_name": user_name, "role": "member", "joined_at": datetime.utcnow().isoformat()}
                if uid not in room.get("allowed_users", []):
                    room.setdefault("allowed_users", []).append(uid)
            else:
                if uid not in s.get("members", {}):
                    s["members"][uid] = {"user_id": uid, "user_name": user_name, "role": "member", "joined_at": datetime.utcnow().isoformat()}
            await save_state()
            return {"joined": True, "server_id": sid, "server_name": s["name"], "channel_id": channel_id, "user_id": uid, "role": role}
    return JSONResponse({"error": "Invalid or expired invite"}, status_code=404)

@app.delete("/s/{sid}/invites/{code}")
async def delete_invite(sid: str, code: str, request: Request):
    s = get_server(sid)
    uid, _ = await identify_user(request)
    require_permission(s, uid, "manage_invites")
    s["invites"] = [i for i in s.get("invites", []) if i["code"] != code]
    await save_state()
    return {"deleted": code}

@app.get("/invites/{code}")
async def invite_landing(code: str):
    for sid, s in servers.items():
        for inv in s.get("invites", []):
            if inv["code"] != code: continue
            valid = True
            if inv.get("expires_at") and datetime.fromisoformat(inv["expires_at"]) < datetime.utcnow(): valid = False
            if inv.get("max_uses") and inv.get("uses", 0) >= inv["max_uses"]: valid = False
            if not valid: return HTMLResponse("<h1>招待が無効です</h1>")
            return HTMLResponse(f"""<!DOCTYPE html>
<html lang="ja">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>FamiChibi 招待</title>
<style>body{{font-family:sans-serif;text-align:center;padding:24px;max-width:480px;margin:auto;}} button{{padding:12px 24px;font-size:1rem;border-radius:8px;border:none;background:#6c5ce7;color:#fff;}}</style>
</head>
<body>
<h1>FamiChibi 招待</h1>
<p>コード: <b>{code}</b></p>
<p id="status">参加中...</p>
<button onclick="join()">参加する</button>
<script>
async function join(){{
  document.getElementById('status').textContent='参加中...';
  try{{
    const res=await fetch('/invites/{code}/join',{{method:'POST'}});
    const data=await res.json();
    if(data.server_id){{ location.href='/?server='+encodeURIComponent(data.server_id)+'&channel='+encodeURIComponent(data.channel_id||''); return; }}
  }}catch(e){{}}
  document.getElementById('status').textContent='招待が無効です';
}}
join();
</script>
</body>
</html>""")
    return HTMLResponse("<h1>招待が見つかりません</h1>")

@app.get("/invites/{code}/qr")
async def invite_qr(code: str, request: Request):
    url = None
    for sid, s in servers.items():
        for inv in s.get("invites", []):
            if inv["code"] == code:
                url = f"/invites/{code}"
                break
        if url: break
    if not url: return JSONResponse({"error": "Invalid invite"}, status_code=404)
    img = qrcode.make(str(request.base_url).rstrip('/') + url)
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    buf.seek(0)
    return StreamingResponse(buf, media_type="image/png")

# ---------------------------------------------------------------------------
# Channels
# ---------------------------------------------------------------------------

async def channel_summary(server: dict, channel: dict, user_id: str) -> dict:
    return {
        "id": channel["id"],
        "name": channel["name"],
        "type": channel.get("type", "text"),
        "ai_enabled": channel.get("ai_enabled", True),
        "visibility": channel.get("visibility", "public"),
        "allowed_roles": channel.get("allowed_roles", []),
        "allowed_users": channel.get("allowed_users", []),
        "has_password": bool(channel.get("password")),
        "user_count": len(channel.get("users", {})),
        "can_manage": has_permission(server, user_id, "manage_channels"),
    }

@app.get("/s/{sid}/channels")
async def list_channels(sid: str, request: Request):
    s = get_server(sid)
    uid, _ = await identify_user(request)
    require_member(s, uid)
    return [await channel_summary(s, r, uid) for rid, r in s.get("rooms", {}).items() if can_view_channel(s, uid, r)]

@app.post("/s/{sid}/channels")
async def create_channel(sid: str, request: Request, name: str = Form(...), channel_type: str = Form("text"), password: Optional[str] = Form(""), visibility: str = Form("public"), ai_enabled: bool = Form(True)):
    s = get_server(sid)
    uid, _ = await identify_user(request)
    require_permission(s, uid, "manage_channels")
    name = name.strip()
    if not name or len(name) > 40:
        return JSONResponse({"error": "Invalid channel name"}, status_code=400)
    if visibility not in ("public", "private"): visibility = "public"
    if channel_type == "voice":
        room = make_voice_channel(name, password, visibility)
    else:
        room = make_text_channel(name, password, visibility)
    room["ai_enabled"] = ai_enabled
    s.setdefault("rooms", {})[room["id"]] = room
    await save_state()
    return await channel_summary(s, room, uid)

@app.get("/s/{sid}/channels/{cid}")
async def get_channel_info(sid: str, cid: str, request: Request):
    s = get_server(sid)
    uid, _ = await identify_user(request)
    room = get_channel(s, cid)
    if not can_view_channel(s, uid, room):
        return JSONResponse({"error": "Forbidden"}, status_code=403)
    return await channel_summary(s, room, uid)

@app.put("/s/{sid}/channels/{cid}")
async def update_channel(sid: str, cid: str, request: Request, name: Optional[str] = Form(None), password: Optional[str] = Form(None), visibility: Optional[str] = Form(None), ai_enabled: Optional[bool] = Form(None), allowed_roles: Optional[str] = Form(None), allowed_users: Optional[str] = Form(None)):
    s = get_server(sid)
    uid, _ = await identify_user(request)
    room = get_channel(s, cid)
    require_permission(s, uid, "manage_channels")
    if name is not None: room["name"] = name.strip()[:40]
    if password is not None: room["password"] = password
    if visibility in ("public", "private"): room["visibility"] = visibility
    if ai_enabled is not None: room["ai_enabled"] = ai_enabled
    if allowed_roles is not None: room["allowed_roles"] = [r.strip() for r in allowed_roles.split(",") if r.strip()]
    if allowed_users is not None: room["allowed_users"] = [u.strip() for u in allowed_users.split(",") if u.strip()]
    await save_state()
    return await channel_summary(s, room, uid)

@app.delete("/s/{sid}/channels/{cid}")
async def delete_channel(sid: str, cid: str, request: Request):
    s = get_server(sid)
    uid, _ = await identify_user(request)
    room = get_channel(s, cid)
    require_permission(s, uid, "manage_channels")
    for photo in room.get("photos", []):
        ppath = static_dir / "photos" / f"{photo.get('id', '')}.jpg"
        if ppath.exists(): ppath.unlink()
    key = f"{sid}:{cid}"
    for conn in manager.active.pop(key, []):
        try: await conn["ws"].close()
        except Exception: pass
    s.get("rooms", {}).pop(cid, None)
    await save_state()
    return {"deleted": cid}


# ---------------------------------------------------------------------------
# Messages
# ---------------------------------------------------------------------------

@app.get("/s/{sid}/channels/{cid}/messages")
async def get_messages(sid: str, cid: str, request: Request, limit: int = 100):
    s = get_server(sid)
    uid, _ = await identify_user(request)
    room = get_channel(s, cid)
    if not can_view_channel(s, uid, room):
        return JSONResponse({"error": "Forbidden"}, status_code=403)
    if room.get("password"):
        pw = request.query_params.get("password", "")
        if room["password"] != pw:
            return JSONResponse({"error": "Channel password required"}, status_code=403)
    msgs = room.get("messages", [])
    return msgs[-limit:]

# ---------------------------------------------------------------------------
# Notebook (notes, tasks, events)
# ---------------------------------------------------------------------------

@app.get("/s/{sid}/channels/{cid}/notes")
async def get_notes(sid: str, cid: str, request: Request):
    s = get_server(sid); uid, _ = await identify_user(request); room = get_channel(s, cid)
    if not can_view_channel(s, uid, room): return JSONResponse({"error": "Forbidden"}, status_code=403)
    return room.get("notes", [])

@app.post("/s/{sid}/channels/{cid}/notes")
async def add_note(sid: str, cid: str, request: Request, content: str = Form(...), category: str = Form("一般"), author_id: Optional[str] = Form(None), author_name: Optional[str] = Form(None)):
    s = get_server(sid); uid, uname = await identify_user(request); room = get_channel(s, cid)
    if not can_view_channel(s, uid, room): return JSONResponse({"error": "Forbidden"}, status_code=403)
    if not has_permission(s, uid, "manage_notes"): return JSONResponse({"error": "Missing permission"}, status_code=403)
    note = {"id": secrets.token_hex(4), "content": content[:MAX_MESSAGE_LENGTH], "category": category, "author_id": author_id or uid, "author_name": author_name or uname, "created_at": datetime.utcnow().isoformat()}
    room.setdefault("notes", []).append(note)
    await save_state(); return note

@app.put("/s/{sid}/channels/{cid}/notes/{nid}")
async def update_note(sid: str, cid: str, nid: str, request: Request, content: str = Form(...)):
    s = get_server(sid); uid, _ = await identify_user(request); room = get_channel(s, cid)
    if not has_permission(s, uid, "manage_notes"): return JSONResponse({"error": "Missing permission"}, status_code=403)
    for n in room.get("notes", []):
        if n["id"] == nid: n["content"] = content[:MAX_MESSAGE_LENGTH]; await save_state(); return n
    raise HTTPException(status_code=404, detail="Note not found")

@app.delete("/s/{sid}/channels/{cid}/notes/{nid}")
async def delete_note(sid: str, cid: str, nid: str, request: Request):
    s = get_server(sid); uid, _ = await identify_user(request); room = get_channel(s, cid)
    if not has_permission(s, uid, "manage_notes"): return JSONResponse({"error": "Missing permission"}, status_code=403)
    room["notes"] = [n for n in room.get("notes", []) if n["id"] != nid]
    await save_state(); return {"deleted": nid}

@app.post("/s/{sid}/channels/{cid}/summarize")
async def summarize_channel(sid: str, cid: str, request: Request):
    s = get_server(sid); uid, _ = await identify_user(request); room = get_channel(s, cid)
    if not has_permission(s, uid, "manage_notes"): return JSONResponse({"error": "Missing permission"}, status_code=403)
    msgs = room.get("messages", [])
    notes = room.get("notes", [])
    tasks = room.get("tasks", [])
    events = room.get("events", [])
    text = f"メッセージ:\n" + "\n".join([f"{m.get('sender','')}: {m.get('content','')}" for m in msgs[-30:]])
    text += f"\n\nメモ:\n" + "\n".join([f"[{n.get('category','')}] {n.get('content','')}" for n in notes[-10:]])
    text += f"\n\nタスク:\n" + "\n".join([f"- {t.get('title','')}: {'完了' if t.get('done') else '未完了'}" for t in tasks[:10]])
    text += f"\n\n予定:\n" + "\n".join([f"- {e.get('title','')} ({e.get('event_at','')})" for e in events[:10]])
    prompt = f"以下の家族の会話とメモを要約してください:\n{text[:MAX_OLLAMA_PROMPT_LENGTH]}"
    summary = await ollama_generate(s, prompt) or "要約を生成できませんでした。"
    note = {"id": secrets.token_hex(4), "content": summary[:MAX_MESSAGE_LENGTH], "category": "要約", "author_id": uid, "author_name": "AI", "created_at": datetime.utcnow().isoformat()}
    room.setdefault("notes", []).append(note)
    await save_state(); return note

@app.get("/s/{sid}/channels/{cid}/tasks")
async def get_tasks(sid: str, cid: str, request: Request):
    s = get_server(sid); uid, _ = await identify_user(request); room = get_channel(s, cid)
    if not can_view_channel(s, uid, room): return JSONResponse({"error": "Forbidden"}, status_code=403)
    return room.get("tasks", [])

@app.post("/s/{sid}/channels/{cid}/tasks")
async def add_task(sid: str, cid: str, request: Request, title: str = Form(...), assignee: Optional[str] = Form(None), due: Optional[str] = Form(None)):
    s = get_server(sid); uid, uname = await identify_user(request); room = get_channel(s, cid)
    if not can_view_channel(s, uid, room): return JSONResponse({"error": "Forbidden"}, status_code=403)
    task = {"id": secrets.token_hex(4), "title": title[:100], "assignee": assignee or uname, "due": due or "", "done": False, "created_at": datetime.utcnow().isoformat()}
    room.setdefault("tasks", []).append(task)
    await save_state(); return task

@app.put("/s/{sid}/channels/{cid}/tasks/{tid}")
async def update_task(sid: str, cid: str, tid: str, request: Request, title: Optional[str] = Form(None), done: Optional[bool] = Form(None), assignee: Optional[str] = Form(None), due: Optional[str] = Form(None)):
    s = get_server(sid); uid, _ = await identify_user(request); room = get_channel(s, cid)
    for t in room.get("tasks", []):
        if t["id"] == tid:
            if title is not None: t["title"] = title[:100]
            if done is not None: t["done"] = done
            if assignee is not None: t["assignee"] = assignee
            if due is not None: t["due"] = due
            await save_state(); return t
    raise HTTPException(status_code=404, detail="Task not found")

@app.delete("/s/{sid}/channels/{cid}/tasks/{tid}")
async def delete_task(sid: str, cid: str, tid: str, request: Request):
    s = get_server(sid); uid, _ = await identify_user(request); room = get_channel(s, cid)
    room["tasks"] = [t for t in room.get("tasks", []) if t["id"] != tid]
    await save_state(); return {"deleted": tid}

@app.get("/s/{sid}/channels/{cid}/events")
async def get_events(sid: str, cid: str, request: Request):
    s = get_server(sid); uid, _ = await identify_user(request); room = get_channel(s, cid)
    if not can_view_channel(s, uid, room): return JSONResponse({"error": "Forbidden"}, status_code=403)
    return room.get("events", [])

@app.post("/s/{sid}/channels/{cid}/events")
async def add_event(sid: str, cid: str, request: Request, title: str = Form(...), event_at: str = Form(...), location: Optional[str] = Form(None)):
    s = get_server(sid); uid, _ = await identify_user(request); room = get_channel(s, cid)
    if not can_view_channel(s, uid, room): return JSONResponse({"error": "Forbidden"}, status_code=403)
    event = {"id": secrets.token_hex(4), "title": title[:100], "event_at": event_at, "location": location or "", "created_at": datetime.utcnow().isoformat()}
    room.setdefault("events", []).append(event)
    await save_state(); return event

@app.put("/s/{sid}/channels/{cid}/events/{eid}")
async def update_event(sid: str, cid: str, eid: str, request: Request, title: Optional[str] = Form(None), event_at: Optional[str] = Form(None), location: Optional[str] = Form(None)):
    s = get_server(sid); uid, _ = await identify_user(request); room = get_channel(s, cid)
    for e in room.get("events", []):
        if e["id"] == eid:
            if title is not None: e["title"] = title[:100]
            if event_at is not None: e["event_at"] = event_at
            if location is not None: e["location"] = location
            await save_state(); return e
    raise HTTPException(status_code=404, detail="Event not found")

@app.delete("/s/{sid}/channels/{cid}/events/{eid}")
async def delete_event(sid: str, cid: str, eid: str, request: Request):
    s = get_server(sid); uid, _ = await identify_user(request); room = get_channel(s, cid)
    room["events"] = [e for e in room.get("events", []) if e["id"] != eid]
    await save_state(); return {"deleted": eid}

# ---------------------------------------------------------------------------
# Bots
# ---------------------------------------------------------------------------

@app.get("/s/{sid}/bots")
async def list_bots(sid: str, request: Request):
    s = get_server(sid); uid, _ = await identify_user(request)
    require_member(s, uid)
    return [{"id": b["id"], "name": b.get("name", ""), "personality": b.get("personality", ""), "voice_enabled": b.get("voice_enabled", True), "proactive_enabled": b.get("proactive_enabled", True), "model": b.get("model", ""), "temperature": b.get("temperature", 0.7), "system_prompt": b.get("system_prompt", ""), "channels": b.get("channels", [])} for b in s.get("bots", [])]

@app.post("/s/{sid}/bots")
async def create_bot(sid: str, request: Request, name: str = Form(...), personality: str = Form("やさしい")):
    s = get_server(sid); uid, _ = await identify_user(request)
    require_permission(s, uid, "manage_bots")
    bot = make_default_bot(name, personality)
    s.setdefault("bots", []).append(bot)
    await save_state(); return {"id": bot["id"], "name": bot["name"]}

@app.put("/s/{sid}/bots/{bid}")
async def update_bot(sid: str, bid: str, request: Request, name: Optional[str] = Form(None), personality: Optional[str] = Form(None), voice_enabled: Optional[bool] = Form(None), proactive_enabled: Optional[bool] = Form(None), model: Optional[str] = Form(None), temperature: Optional[float] = Form(None), system_prompt: Optional[str] = Form(None), channels: Optional[str] = Form(None)):
    s = get_server(sid); uid, _ = await identify_user(request)
    require_permission(s, uid, "manage_bots")
    for b in s.get("bots", []):
        if b["id"] == bid:
            if name is not None: b["name"] = name
            if personality is not None: b["personality"] = personality
            if voice_enabled is not None: b["voice_enabled"] = voice_enabled
            if proactive_enabled is not None: b["proactive_enabled"] = proactive_enabled
            if model is not None: b["model"] = model
            if temperature is not None: b["temperature"] = temperature
            if system_prompt is not None: b["system_prompt"] = system_prompt
            if channels is not None: b["channels"] = [c.strip() for c in channels.split(",") if c.strip()]
            await save_state(); return b
    raise HTTPException(status_code=404, detail="Bot not found")

@app.delete("/s/{sid}/bots/{bid}")
async def delete_bot(sid: str, bid: str, request: Request):
    s = get_server(sid); uid, _ = await identify_user(request)
    require_permission(s, uid, "manage_bots")
    s["bots"] = [b for b in s.get("bots", []) if b["id"] != bid]
    await save_state(); return {"deleted": bid}

# ---------------------------------------------------------------------------
# Knowledge base
# ---------------------------------------------------------------------------

@app.get("/s/{sid}/knowledge")
async def list_knowledge(sid: str, request: Request):
    s = get_server(sid); uid, _ = await identify_user(request)
    require_member(s, uid)
    return s.get("knowledge_base", [])

@app.post("/s/{sid}/knowledge")
async def add_knowledge(sid: str, request: Request, title: str = Form(...), content: str = Form(...)):
    s = get_server(sid); uid, _ = await identify_user(request)
    require_permission(s, uid, "manage_bots")
    entry = {"id": secrets.token_hex(4), "title": title[:100], "content": content[:2000], "created_at": datetime.utcnow().isoformat()}
    s.setdefault("knowledge_base", []).append(entry)
    await save_state(); return entry

@app.put("/s/{sid}/knowledge/{kid}")
async def update_knowledge(sid: str, kid: str, request: Request, title: Optional[str] = Form(None), content: Optional[str] = Form(None)):
    s = get_server(sid); uid, _ = await identify_user(request)
    require_permission(s, uid, "manage_bots")
    for k in s.get("knowledge_base", []):
        if k["id"] == kid:
            if title is not None: k["title"] = title[:100]
            if content is not None: k["content"] = content[:2000]
            await save_state(); return k
    raise HTTPException(status_code=404, detail="Knowledge entry not found")

@app.delete("/s/{sid}/knowledge/{kid}")
async def delete_knowledge(sid: str, kid: str, request: Request):
    s = get_server(sid); uid, _ = await identify_user(request)
    require_permission(s, uid, "manage_bots")
    s["knowledge_base"] = [k for k in s.get("knowledge_base", []) if k["id"] != kid]
    await save_state(); return {"deleted": kid}


# ---------------------------------------------------------------------------
# Voice signaling
# ---------------------------------------------------------------------------

voice_state: Dict[str, Dict[str, dict]] = {}  # sid -> cid -> {uid: offer/sdp/ice}

@app.post("/s/{sid}/channels/{cid}/voice/join")
async def voice_join(sid: str, cid: str, request: Request):
    s = get_server(sid); uid, uname = await identify_user(request); room = get_channel(s, cid)
    if room.get("type") != "voice": return JSONResponse({"error": "Not a voice channel"}, status_code=400)
    if not can_view_channel(s, uid, room): return JSONResponse({"error": "Forbidden"}, status_code=403)
    if not has_permission(s, uid, "use_voice"): return JSONResponse({"error": "Missing voice permission"}, status_code=403)
    room.setdefault("users", {})[uid] = {"user_id": uid, "user_name": uname, "joined_at": datetime.utcnow().isoformat()}
    await save_state()
    return {"users": [{"user_id": u, "user_name": info.get("user_name", "")} for u, info in room.get("users", {}).items()]}

@app.post("/s/{sid}/channels/{cid}/voice/leave")
async def voice_leave(sid: str, cid: str, request: Request):
    s = get_server(sid); uid, _ = await identify_user(request); room = get_channel(s, cid)
    room.get("users", {}).pop(uid, None)
    await save_state()
    return {"left": True}

@app.post("/s/{sid}/channels/{cid}/voice/offer")
async def voice_offer(sid: str, cid: str, request: Request, target_user_id: str = Form(...), sdp: str = Form(...)):
    s = get_server(sid); uid, _ = await identify_user(request); room = get_channel(s, cid)
    voice_state.setdefault(sid, {}).setdefault(cid, {})[target_user_id] = {"from": uid, "type": "offer", "sdp": sdp}
    return {"ok": True}

@app.post("/s/{sid}/channels/{cid}/voice/answer")
async def voice_answer(sid: str, cid: str, request: Request, target_user_id: str = Form(...), sdp: str = Form(...)):
    s = get_server(sid); uid, _ = await identify_user(request); room = get_channel(s, cid)
    voice_state.setdefault(sid, {}).setdefault(cid, {})[target_user_id] = {"from": uid, "type": "answer", "sdp": sdp}
    return {"ok": True}

@app.post("/s/{sid}/channels/{cid}/voice/ice")
async def voice_ice(sid: str, cid: str, request: Request, target_user_id: str = Form(...), candidate: str = Form(...), sdp_mid: Optional[str] = Form(None), sdp_mline_index: Optional[int] = Form(None)):
    s = get_server(sid); uid, _ = await identify_user(request); room = get_channel(s, cid)
    voice_state.setdefault(sid, {}).setdefault(cid, {})[target_user_id] = {"from": uid, "type": "ice", "candidate": candidate, "sdp_mid": sdp_mid, "sdp_mline_index": sdp_mline_index}
    return {"ok": True}

@app.get("/s/{sid}/channels/{cid}/voice/poll")
async def voice_poll(sid: str, cid: str, request: Request):
    s = get_server(sid); uid, _ = await identify_user(request)
    data = voice_state.get(sid, {}).get(cid, {}).pop(uid, None)
    return data or {}

# ---------------------------------------------------------------------------
# Photos
# ---------------------------------------------------------------------------

@app.post("/s/{sid}/channels/{cid}/photos")
async def upload_photo(sid: str, cid: str, request: Request, file: UploadFile = File(...), uploader_id: Optional[str] = Form(None), uploader_name: Optional[str] = Form(None)):
    s = get_server(sid); uid, uname = await identify_user(request); room = get_channel(s, cid)
    if not can_view_channel(s, uid, room): return JSONResponse({"error": "Forbidden"}, status_code=403)
    if not has_permission(s, uid, "send_messages"): return JSONResponse({"error": "Missing permission"}, status_code=403)
    if room.get("type") != "text": return JSONResponse({"error": "Photos only in text channels"}, status_code=400)
    contents = await file.read()
    if len(contents) > MAX_FILE_SIZE: return JSONResponse({"error": "File too large"}, status_code=413)
    pid = secrets.token_hex(8)
    path = static_dir / "photos" / f"{pid}.jpg"
    async with aiofiles.open(path, "wb") as f: await f.write(contents)
    photo = {"id": pid, "url": f"/static/photos/{pid}.jpg", "uploader_id": uploader_id or uid, "uploader_name": uploader_name or uname, "created_at": datetime.utcnow().isoformat()}
    room.setdefault("photos", []).append(photo)
    await save_state()
    for bot in s.get("bots", []):
        if bot.get("proactive_enabled") and (not bot.get("channels") or cid in bot.get("channels", [])):
            comment = await generate_photo_comment(s, bot, uploader_name or uname)
            if comment:
                msg = {"id": secrets.token_hex(8), "sender": bot["name"], "sender_id": bot["id"], "content": comment, "type": "agent", "timestamp": datetime.utcnow().isoformat()}
                room.setdefault("messages", []).append(msg)
                await manager.broadcast(sid, cid, msg)
    await save_state()
    return photo

@app.get("/s/{sid}/channels/{cid}/photos")
async def list_photos(sid: str, cid: str, request: Request):
    s = get_server(sid); uid, _ = await identify_user(request); room = get_channel(s, cid)
    if not can_view_channel(s, uid, room): return JSONResponse({"error": "Forbidden"}, status_code=403)
    return room.get("photos", [])

# ---------------------------------------------------------------------------
# WebSocket manager
# ---------------------------------------------------------------------------

class ConnectionManager:
    def __init__(self):
        self.active: Dict[str, List[dict]] = {}

    async def connect(self, ws: WebSocket, sid: str, cid: str, user_id: str, user_name: str):
        await ws.accept()
        key = f"{sid}:{cid}"
        self.active.setdefault(key, []).append({"ws": ws, "user_id": user_id, "user_name": user_name})
        s = get_server(sid); room = get_channel(s, cid)
        room.setdefault("users", {})[user_id] = {"user_id": user_id, "user_name": user_name, "joined_at": datetime.utcnow().isoformat()}
        await self.broadcast_system(sid, cid, f"{format_name(user_name)} が参加しました")
        return key

    async def disconnect(self, ws: WebSocket, key: str):
        conns = self.active.get(key, [])
        entry = None
        for c in conns:
            if c["ws"] == ws: entry = c; break
        if entry:
            conns.remove(entry)
            try: await ws.close()
            except Exception: pass
            sid, cid = key.split(":", 1)
            s = get_server(sid); room = get_channel(s, cid)
            room.get("users", {}).pop(entry["user_id"], None)
            await self.broadcast_system(sid, cid, f"{format_name(entry['user_name'])} が退出しました")

    async def broadcast(self, sid: str, cid: str, message: dict, exclude: Optional[WebSocket] = None):
        key = f"{sid}:{cid}"
        dead = []
        for c in self.active.get(key, []):
            if exclude and c["ws"] == exclude: continue
            try:
                await c["ws"].send_text(json.dumps(message, ensure_ascii=False))
            except Exception:
                dead.append(c)
        for d in dead:
            try: self.active[key].remove(d)
            except ValueError: pass

    async def broadcast_system(self, sid: str, cid: str, text: str):
        await self.broadcast(sid, cid, {"type": "system", "content": text, "timestamp": datetime.utcnow().isoformat()})

    async def broadcast_signal(self, sid: str, cid: str, signal: dict, target_user_id: Optional[str] = None):
        key = f"{sid}:{cid}"
        dead = []
        for c in self.active.get(key, []):
            if target_user_id and c["user_id"] != target_user_id: continue
            try:
                await c["ws"].send_text(json.dumps({"type": "voice_signal", "data": signal}, ensure_ascii=False))
            except Exception:
                dead.append(c)
        for d in dead:
            try: self.active[key].remove(d)
            except ValueError: pass

manager = ConnectionManager()

# ---------------------------------------------------------------------------
# WebSocket endpoint
# ---------------------------------------------------------------------------

@app.websocket("/ws/{sid}/{cid}")
async def websocket_endpoint(ws: WebSocket, sid: str, cid: str):
    s = get_server(sid); room = get_channel(s, cid)
    user_id = ws.query_params.get("user_id") or str(uuid.uuid4())
    user_name = ws.query_params.get("user_name") or "匿名"
    password = ws.query_params.get("password", "")
    if not can_view_channel(s, user_id, room):
        await ws.close(code=1008, reason="Forbidden")
        return
    if room.get("password") and room["password"] != password:
        await ws.close(code=1008, reason="Channel password required")
        return
    if not has_permission(s, user_id, "send_messages") and room.get("type") == "text":
        await ws.close(code=1008, reason="Missing send permission")
        return
    key = await manager.connect(ws, sid, cid, user_id, user_name)
    try:
        while True:
            raw = await ws.receive_text()
            data = json.loads(raw)
            msg_type = data.get("type", "message")
            if msg_type == "message":
                content = data.get("content", "")
                if len(content) > MAX_MESSAGE_LENGTH: content = content[:MAX_MESSAGE_LENGTH]
                if not content.strip(): continue
                msg = {"id": secrets.token_hex(8), "sender": user_name, "sender_id": user_id, "content": content.strip(), "type": "chat", "timestamp": datetime.utcnow().isoformat()}
                room.setdefault("messages", []).append(msg)
                await save_state()
                await manager.broadcast(sid, cid, msg)
                if room.get("ai_enabled", True):
                    for bot in s.get("bots", []):
                        if bot.get("proactive_enabled") and (not bot.get("channels") or cid in bot.get("channels", [])):
                            reply = await generate_agent_reply(s, bot, room, content, user_name)
                            if reply:
                                bot_msg = {"id": secrets.token_hex(8), "sender": bot["name"], "sender_id": bot["id"], "content": reply, "type": "agent", "timestamp": datetime.utcnow().isoformat()}
                                room.setdefault("messages", []).append(bot_msg)
                                await manager.broadcast(sid, cid, bot_msg)
                                await save_state()
            elif msg_type == "reaction":
                target_id = data.get("target_id")
                reaction = data.get("reaction", "いいね")
                found = None
                for m in room.get("messages", []):
                    if m.get("id") == target_id: found = m; break
                if found:
                    await manager.broadcast(sid, cid, {"type": "reaction", "target_id": target_id, "reaction": reaction, "sender": user_name, "timestamp": datetime.utcnow().isoformat()})
            elif msg_type == "whisper":
                target = data.get("target_user_id")
                content = data.get("content", "")
                await manager.broadcast_signal(sid, cid, {"type": "whisper", "from_user_id": user_id, "from_user_name": user_name, "content": content}, target)
            elif msg_type == "nudge":
                target = data.get("target_user_id")
                await manager.broadcast_signal(sid, cid, {"type": "nudge", "from_user_id": user_id, "from_user_name": user_name}, target)
            elif msg_type == "voice_signal":
                signal = data.get("data", {})
                await manager.broadcast_signal(sid, cid, signal, signal.get("target_user_id"))
    except WebSocketDisconnect:
        await manager.disconnect(ws, key)
    except Exception as e:
        print(f"[WebSocket] error: {e}")
        await manager.disconnect(ws, key)

# ---------------------------------------------------------------------------
# Catch-all for SPA
# ---------------------------------------------------------------------------

@app.get("/{path:path}")
async def spa_catchall(path: str, request: Request):
    if path.startswith("s/") or path.startswith("admin") or path.startswith("static") or path.startswith("discover") or path.startswith("servers") or path.startswith("voicevox") or path.startswith("invites") or path.startswith("ws"):
        raise HTTPException(status_code=404, detail="Not found")
    return HTMLResponse(content=_web_html if _web_html else "<h1>Web client not found</h1>")

def start_discovery_server():
    port = 8001
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    try:
        sock.bind(("", port))
    except Exception as e:
        print(f"[Discovery] bind failed: {e}")
        return
    print(f"[Discovery] UDP listener on port {port}")
    while True:
        try:
            data, addr = sock.recvfrom(1024)
            if data.decode("utf-8", errors="ignore").strip() == "FamiChibi-discover":
                response = json.dumps({"name": "FamiChibi", "port": 8000, "version": "4.0.0"})
                sock.sendto(response.encode("utf-8"), addr)
        except Exception as e:
            print(f"[Discovery] error: {e}")

@app.on_event("startup")
async def _start_discovery():
    threading.Thread(target=start_discovery_server, daemon=True).start()

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
