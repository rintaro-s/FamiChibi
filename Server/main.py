import asyncio
import atexit
import json
import os
import random
import secrets
import shutil
import uuid
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional, Any, Set

import aiofiles
import httpx
from fastapi import (
    FastAPI, WebSocket, WebSocketDisconnect, Form, Request,
    UploadFile, File, HTTPException
)
from fastapi.responses import HTMLResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware

app = FastAPI(title="FamiChibi Server", version="3.0.0")
BASE_DIR = Path(__file__).resolve().parent
WORKSPACE_ROOT = BASE_DIR.parent
DATA_DIR = BASE_DIR / "data"
DATA_DIR.mkdir(exist_ok=True)
STATE_FILE = DATA_DIR / "servers.json"
ADMIN_TOKEN_FILE = DATA_DIR / "admin_token.txt"

# Admin token persistence
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
    "admin": {"permissions": ["manage_channels", "manage_bots", "manage_members", "manage_notes", "send_messages", "use_voice"]},
    "moderator": {"permissions": ["manage_members", "manage_notes", "send_messages", "use_voice"]},
    "member": {"permissions": ["send_messages", "use_voice", "manage_notes"]},
    "guest": {"permissions": ["send_messages"]},
}

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
        "channels": [],  # empty = all channels
    }

def make_default_server(server_id: str = "default", name: str = "Default") -> dict:
    return {
        "id": server_id,
        "name": name,
        "password": "",
        "icon": "",
        "welcome_message": "",
        "ollama_url": "",
        "ollama_model": "llama3",
        "voicevox_speaker": 0,
        "created_at": datetime.utcnow().isoformat(),
        "owner_id": "",
        "roles": dict(DEFAULT_ROLES),
        "members": {},  # user_id -> {user_name, role, joined_at}
        "bots": [make_default_bot()],
        "knowledge_base": [],  # {id, title, content, created_at}
        "rooms": {},
    }

def make_text_channel(name: str, password: str = "") -> dict:
    return {
        "id": secrets.token_hex(4),
        "name": name,
        "type": "text",
        "password": password,
        "created_at": datetime.utcnow().isoformat(),
        "ai_enabled": True,
        "messages": [],
        "agents": [],  # backward compat: room-level agents
        "users": {},
        "notes": [],
        "tasks": [],
        "events": [],
        "photos": [],
    }

def make_voice_channel(name: str, password: str = "") -> dict:
    return {
        "id": secrets.token_hex(4),
        "name": name,
        "type": "voice",
        "password": password,
        "created_at": datetime.utcnow().isoformat(),
        "users": {},  # user_id -> {user_name, joined_at}
    }

# In-memory state
servers: Dict[str, dict] = {}

# ---------------------------------------------------------------------------
# Persistence (async)
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
            # Migrate old data: ensure new fields exist
            for sid, s in servers.items():
                s.setdefault("owner_id", "")
                s.setdefault("roles", dict(DEFAULT_ROLES))
                s.setdefault("members", {})
                s.setdefault("bots", s.pop("agents", [make_default_bot()]) if "agents" in s else [make_default_bot()])
                s.setdefault("knowledge_base", [])
                s.setdefault("icon", "")
                s.setdefault("welcome_message", "")
                for rid, r in s.get("rooms", {}).items():
                    r.setdefault("type", "text")
                    r.setdefault("ai_enabled", True)
                    r.setdefault("agents", [])
            print("[State] Loaded from disk")
        except Exception as e:
            print(f"[State] Load failed: {e}")
            backup = STATE_FILE.with_suffix(f".bak.{datetime.utcnow().strftime('%Y%m%d%H%M%S')}")
            try:
                shutil.copy2(STATE_FILE, backup)
            except Exception:
                pass
            servers = {}
    if not servers:
        servers["default"] = make_default_server("default", "Default")
        asyncio.get_event_loop().run_until_complete(save_state())

load_state()
def _atexit_save():
    try:
        loop = asyncio.get_event_loop()
        if loop.is_running():
            loop.create_task(save_state())
        else:
            loop.run_until_complete(save_state())
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
    # Ensure sample VRM exists in static
    src_vrm = WORKSPACE_ROOT / "AvatarSample_M.vrm"
    dst_dir = BASE_DIR / "static" / "avatars"
    dst_dir.mkdir(parents=True, exist_ok=True)
    dst_vrm = dst_dir / "AvatarSample_M.vrm"
    if src_vrm.exists() and not dst_vrm.exists():
        shutil.copy2(src_vrm, dst_vrm)
        print(f"[Setup] Copied sample VRM to {dst_vrm}")

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

HONORIFIC_SUFFIXES = ("ちゃん", "くん", "さん", "様", "殿", "先生", "先輩", "後輩", "君")

def format_name(name: str) -> str:
    if not name:
        return "匿名"
    name = name.strip()
    if name.endswith(HONORIFIC_SUFFIXES):
        return name
    return f"{name}さん"

def require_local(request: Request):
    client = request.client.host if request.client else ""
    # Check X-Forwarded-For if behind proxy
    forwarded = request.headers.get("x-forwarded-for", "")
    if forwarded:
        # If proxy is local but forwarded is external, reject
        if client in ("127.0.0.1", "::1", "localhost"):
            client = forwarded.split(",")[0].strip()
    if client in ("127.0.0.1", "::1", "localhost"):
        return
    if client.startswith("10.") or client.startswith("192.168."):
        return
    if client.startswith("172."):
        try:
            second = int(client.split(".")[1])
            if 16 <= second <= 31:
                return
        except Exception:
            pass
    raise HTTPException(status_code=403, detail="Admin access restricted to local network")

def require_admin_token(request: Request):
    token = request.query_params.get("token", "") or request.headers.get("x-admin-token", "")
    if token != ADMIN_TOKEN:
        raise HTTPException(status_code=403, detail="Invalid admin token")

MAX_MESSAGE_LENGTH = 2000
MAX_OLLAMA_PROMPT_LENGTH = 5000
MAX_FILE_SIZE = 10 * 1024 * 1024

# ---------------------------------------------------------------------------
# Ollama integration (SSRF-safe)
# ---------------------------------------------------------------------------

PRIVATE_IP_PREFIXES = ("10.", "192.168.", "172.16.", "172.17.", "172.18.", "172.19.", "172.20.", "172.21.", "172.22.", "172.23.", "172.24.", "172.25.", "172.26.", "172.27.", "172.28.", "172.29.", "172.30.", "172.31.", "127.", "0.", "localhost", "::1")

def validate_ollama_url(url: str) -> bool:
    if not url:
        return True
    url_lower = url.lower()
    if not url_lower.startswith(("http://", "https://")):
        return False
    for prefix in PRIVATE_IP_PREFIXES:
        if prefix in url_lower:
            return False
    return True

async def ollama_generate(server: dict, prompt: str) -> Optional[str]:
    url = server.get("ollama_url", "").rstrip("/")
    model = server.get("ollama_model", "llama3")
    if not url:
        return None
    if not validate_ollama_url(url):
        print("[Ollama] SSRF blocked")
        return None
    if len(prompt) > MAX_OLLAMA_PROMPT_LENGTH:
        prompt = prompt[:MAX_OLLAMA_PROMPT_LENGTH]
    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            res = await client.post(
                f"{url}/api/generate",
                json={"model": model, "prompt": prompt, "stream": False}
            )
            data = res.json()
            return data.get("response", "")
    except Exception as e:
        print(f"[Ollama] error: {e}")
        return None

async def generate_agent_reply(server: dict, bot: dict, channel: dict, message: str, sender: str) -> Optional[str]:
    notes = channel.get("notes", [])
    tasks = [t for t in channel.get("tasks", []) if not t.get("done")]
    events = channel.get("events", [])
    recent_msgs = channel.get("messages", [])[-10:]
    knowledge = server.get("knowledge_base", [])

    prompt_parts = [
        f"あなたは{bot['name']}です。性格: {bot.get('personality', 'やさしい')}。",
        "家族の最近のメッセージとメモ:"
    ]
    for m in recent_msgs:
        prompt_parts.append(f"- {m.get('sender', '')}: {m.get('content', '')}")
    if notes:
        prompt_parts.append("メモ:")
        for n in notes[-5:]:
            prompt_parts.append(f"- [{n.get('category', '一般')}] {n.get('content', '')}")
    if tasks:
        prompt_parts.append("お手伝い:")
        for t in tasks[:3]:
            prompt_parts.append(f"- {t.get('title', '')}")
    if events:
        prompt_parts.append("予定:")
        for e in events[:3]:
            prompt_parts.append(f"- {e.get('title', '')} ({e.get('event_at', '')})")
    if knowledge:
        # Simple keyword matching for relevant knowledge
        relevant = [k for k in knowledge if any(w in message for w in k.get("title", "").split())][:3]
        if relevant:
            prompt_parts.append("参考知識:")
            for k in relevant:
                prompt_parts.append(f"- {k.get('title', '')}: {k.get('content', '')[:200]}")
    prompt_parts.append(f"{format_name(sender)}のメッセージ:「{message[:500]}」")
    prompt_parts.append("やさしく簡潔に返信してください。")
    prompt = "\n".join(prompt_parts)

    ollama_reply = await ollama_generate(server, prompt)
    if ollama_reply:
        return ollama_reply.strip()

    # Fallback rule-based
    replies = [
        f"{format_name(sender)}、がんばって！",
        "それはすごいね！応援してるよ！",
        f"ふふっ、{format_name(sender)}らしいね。",
        "大丈夫、きっとうまくいくよ！",
        f"{format_name(sender)}のこと、信じてる！"
    ]
    personality = bot.get("personality", "")
    if "ツンデレ" in personality:
        replies = [
            f"べ、別に{sender}のためじゃないんだから！",
            "ちょっとがんばりすぎよ...",
            f"まあ、{sender}ならできるんじゃない？"
        ]
    elif "おしとやか" in personality:
        replies = [
            f"{format_name(sender)}、応援しておりますわ。",
            f"素敵ですわね、{format_name(sender)}",
            "ご無理はなさらないでくださいませ"
        ]
    elif "元気" in personality:
        replies = [
            f"{sender}！超がんばれー！！",
            f"イェーイ！{sender}最高！！",
            "おおお！それはアツいね！"
        ]
    return random.choice(replies)

async def generate_briefing(server: dict, channel: dict, sender: str) -> Optional[str]:
    prompt = f"{format_name(sender)}への朝の挨拶と、今日の家族のメモ・予定・お手伝いの概要を2〜3行で。"
    notes = channel.get("notes", [])
    tasks = [t for t in channel.get("tasks", []) if not t.get("done")]
    events = channel.get("events", [])
    ctx = []
    if tasks:
        ctx.append(f"未完了のお手伝いが{len(tasks)}件あります。")
    if events:
        ctx.append(f"今日の予定: {events[0].get('title', '')}。")
    if notes:
        ctx.append(f"メモ: {notes[0].get('content', '')}。")
    if not ctx:
        ctx.append("今日は特に予定がないみたい。")
    full = prompt + "\n" + "\n".join(ctx)
    ollama_reply = await ollama_generate(server, full)
    if ollama_reply:
        return ollama_reply.strip()
    return f"{format_name(sender)}、おはようございます。\n" + "\n".join(ctx)

async def generate_photo_comment(server: dict, bot: dict, uploader: str) -> Optional[str]:
    prompt = f"{format_name(uploader)}が写真を共有しました。写真へのやさしいコメントを1行で。"
    return await ollama_generate(server, prompt) or f"{format_name(uploader)}、素敵な写真をありがとう！"

# ---------------------------------------------------------------------------
# VOICEVOX proxy
# ---------------------------------------------------------------------------

VOICEVOX_BASE = os.environ.get("VOICEVOX_URL", "http://localhost:50021")

@app.post("/voicevox/synthesize")
async def voicevox_synthesize(text: str = Form(...), speaker: int = Form(0)):
    if len(text) > 500:
        return JSONResponse({"error": "Text too long (max 500 chars)"}, status_code=400)
    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            aq = await client.post(
                f"{VOICEVOX_BASE}/audio_query",
                params={"text": text, "speaker": speaker}
            )
            query = aq.json()
            synth = await client.post(
                f"{VOICEVOX_BASE}/synthesis",
                params={"speaker": speaker},
                content=json.dumps(query),
                headers={"Content-Type": "application/json"}
            )
            return Response(content=synth.content, media_type="audio/wav")
    except Exception as e:
        return JSONResponse({"error": str(e)}, status_code=503)

# ---------------------------------------------------------------------------
# Static / templates
# ---------------------------------------------------------------------------

static_dir = BASE_DIR / "static"
if not static_dir.exists():
    static_dir = WORKSPACE_ROOT / "static"
template_dir = BASE_DIR / "templates"
if not template_dir.exists():
    template_dir = WORKSPACE_ROOT / "templates"

os.makedirs(static_dir, exist_ok=True)
os.makedirs(static_dir / "avatars", exist_ok=True)
os.makedirs(static_dir / "photos", exist_ok=True)
os.makedirs(static_dir / "web", exist_ok=True)

app.mount("/static", StaticFiles(directory=str(static_dir)), name="static")

_web_html = ""
_admin_html = ""

try:
    with open(static_dir / "web" / "index.html", "r", encoding="utf-8") as f:
        _web_html = f.read()
except Exception:
    pass

try:
    with open(template_dir / "admin.html", "r", encoding="utf-8") as f:
        _admin_html = f.read()
except Exception:
    pass

# ---------------------------------------------------------------------------
# Admin panel
# ---------------------------------------------------------------------------

@app.get("/admin", response_class=HTMLResponse)
async def admin_ui(request: Request):
    require_local(request)
    # Also require token for viewing the UI
    token = request.query_params.get("token", "")
    if token != ADMIN_TOKEN:
        return HTMLResponse("<h1>Admin Access Denied</h1><p>Invalid or missing token.</p>", status_code=403)
    return _admin_html if _admin_html else "<h1>Admin panel not found</h1>"

@app.get("/admin/api/servers")
async def admin_list_servers(request: Request):
    require_local(request)
    require_admin_token(request)
    return [
        {
            "id": sid,
            "name": s["name"],
            "room_count": len(s.get("rooms", {})),
            "has_password": bool(s.get("password")),
            "ollama_url": s.get("ollama_url", ""),
            "ollama_model": s.get("ollama_model", ""),
            "voicevox_speaker": s.get("voicevox_speaker", 0),
            "member_count": len(s.get("members", {})),
            "bot_count": len(s.get("bots", [])),
        }
        for sid, s in servers.items()
    ]

@app.post("/admin/api/servers")
async def admin_create_server(request: Request, name: str = Form(...), password: Optional[str] = Form("")):
    require_local(request)
    require_admin_token(request)
    sid = "srv_" + secrets.token_hex(4)
    servers[sid] = make_default_server(sid, name)
    servers[sid]["password"] = password
    await save_state()
    return {"id": sid, "name": name}

@app.delete("/admin/api/servers/{sid}")
async def admin_delete_server(sid: str, request: Request):
    require_local(request)
    require_admin_token(request)
    s = get_server(sid)
    # Clean up photos
    for rid, room in s.get("rooms", {}).items():
        for photo in room.get("photos", []):
            pid = photo.get("id", "")
            ppath = static_dir / "photos" / f"{pid}.jpg"
            if ppath.exists():
                ppath.unlink()
    # Clean up WebSocket connections
    for rid in list(s.get("rooms", {}).keys()):
        key = f"{sid}:{rid}"
        if key in manager.active:
            for conn in manager.active.pop(key, []):
                try:
                    await conn["ws"].close()
                except Exception:
                    pass
    del servers[sid]
    await save_state()
    return {"deleted": sid}

@app.post("/admin/api/servers/{sid}/config")
async def admin_update_config(
    sid: str,
    request: Request,
    name: Optional[str] = Form(None),
    password: Optional[str] = Form(None),
    ollama_url: Optional[str] = Form(None),
    ollama_model: Optional[str] = Form(None),
    voicevox_speaker: Optional[int] = Form(None),
):
    require_local(request)
    require_admin_token(request)
    s = get_server(sid)
    if name is not None:
        s["name"] = name
    if password is not None and password != "__NOCHANGE__":
        s["password"] = password
    if ollama_url is not None:
        s["ollama_url"] = ollama_url
    if ollama_model is not None:
        s["ollama_model"] = ollama_model
    if voicevox_speaker is not None:
        s["voicevox_speaker"] = voicevox_speaker
    await save_state()
    return {"ok": True}

@app.get("/admin/api/servers/{sid}/rooms")
async def admin_list_rooms(sid: str, request: Request):
    require_local(request)
    require_admin_token(request)
    s = get_server(sid)
    return [
        {
            "id": rid,
            "name": r["name"],
            "type": r.get("type", "text"),
            "user_count": len(r.get("users", {})),
            "has_password": bool(r.get("password")),
        }
        for rid, r in s.get("rooms", {}).items()
    ]

# ---------------------------------------------------------------------------
# Discovery
# ---------------------------------------------------------------------------

@app.get("/discover")
async def discover():
    import sys
    port = 8000
    for arg in sys.argv:
        if arg.startswith("--port="):
            try:
                port = int(arg.split("=", 1)[1])
            except Exception:
                pass
    return {
        "name": "FamiChibi Server",
        "version": "3.0.0",
        "port": port,
        "features": [
            "rooms", "password", "avatars", "websocket", "notebook",
            "tasks", "events", "photos", "whisper", "nudge", "reactions",
            "agent_memory", "ollama", "voicevox", "admin_panel",
            "voice_channels", "ai_bots", "permissions", "knowledge_base"
        ]
    }

@app.get("/servers")
async def list_servers():
    return [
        {"id": sid, "name": s["name"], "has_password": bool(s.get("password")), "icon": s.get("icon", "")}
        for sid, s in servers.items()
    ]

# ---------------------------------------------------------------------------
# Connection manager (scoped by server_id + room_id)
# ---------------------------------------------------------------------------

class ConnectionManager:
    def __init__(self):
        self.active: Dict[str, List[dict]] = {}

    def _key(self, server_id: str, room_id: str) -> str:
        return f"{server_id}:{room_id}"

    async def connect(self, server_id: str, room_id: str, ws: WebSocket, user_id: str, user_name: str):
        key = self._key(server_id, room_id)
        if key not in self.active:
            self.active[key] = []
        self.active[key].append({"ws": ws, "user_id": user_id, "user_name": user_name})

    def disconnect(self, server_id: str, room_id: str, ws: WebSocket):
        key = self._key(server_id, room_id)
        if key in self.active:
            self.active[key] = [c for c in self.active[key] if c["ws"] != ws]
            if not self.active[key]:
                del self.active[key]

    def get_users(self, server_id: str, room_id: str) -> List[dict]:
        key = self._key(server_id, room_id)
        return [{"user_id": c["user_id"], "user_name": c["user_name"]} for c in self.active.get(key, [])]

    async def broadcast(self, server_id: str, room_id: str, message: dict, exclude_ws: Optional[WebSocket] = None):
        key = self._key(server_id, room_id)
        dead = []
        for conn in list(self.active.get(key, [])):
            if conn["ws"] == exclude_ws:
                continue
            try:
                await conn["ws"].send_json(message)
            except Exception:
                dead.append(conn)
        for d in dead:
            try:
                self.active.get(key, []).remove(d)
            except ValueError:
                pass

    async def send_to(self, server_id: str, room_id: str, user_id: str, message: dict):
        key = self._key(server_id, room_id)
        for conn in list(self.active.get(key, [])):
            if conn["user_id"] == user_id:
                try:
                    await conn["ws"].send_json(message)
                except Exception:
                    pass

manager = ConnectionManager()

# ---------------------------------------------------------------------------
# Server-level API (settings, members, bots, knowledge)
# ---------------------------------------------------------------------------

@app.get("/s/{sid}")
async def get_server_info(sid: str):
    s = get_server(sid)
    return {
        "id": s["id"],
        "name": s["name"],
        "icon": s.get("icon", ""),
        "welcome_message": s.get("welcome_message", ""),
        "has_password": bool(s.get("password")),
        "member_count": len(s.get("members", {})),
        "bot_count": len(s.get("bots", [])),
        "channels": [
            {"id": rid, "name": r["name"], "type": r.get("type", "text"), "has_password": bool(r.get("password"))}
            for rid, r in s.get("rooms", {}).items()
        ]
    }

@app.post("/s/{sid}/settings")
async def update_server_settings(
    sid: str,
    name: Optional[str] = Form(None),
    icon: Optional[str] = Form(None),
    welcome_message: Optional[str] = Form(None),
    password: Optional[str] = Form(None),
    ollama_url: Optional[str] = Form(None),
    ollama_model: Optional[str] = Form(None),
):
    s = get_server(sid)
    if name is not None:
        s["name"] = name
    if icon is not None:
        s["icon"] = icon
    if welcome_message is not None:
        s["welcome_message"] = welcome_message
    if password is not None:
        s["password"] = password
    if ollama_url is not None:
        s["ollama_url"] = ollama_url
    if ollama_model is not None:
        s["ollama_model"] = ollama_model
    await save_state()
    return {"ok": True}

@app.get("/s/{sid}/members")
async def list_members(sid: str):
    s = get_server(sid)
    return [
        {"user_id": uid, "user_name": m.get("user_name", ""), "role": m.get("role", "member"), "joined_at": m.get("joined_at", "")}
        for uid, m in s.get("members", {}).items()
    ]

@app.post("/s/{sid}/members/{uid}/role")
async def update_member_role(sid: str, uid: str, role: str = Form(...)):
    s = get_server(sid)
    if uid not in s.get("members", {}):
        raise HTTPException(status_code=404, detail="Member not found")
    s["members"][uid]["role"] = role
    await save_state()
    return {"ok": True}

@app.delete("/s/{sid}/members/{uid}")
async def remove_member(sid: str, uid: str):
    s = get_server(sid)
    s.get("members", {}).pop(uid, None)
    await save_state()
    return {"ok": True}

@app.get("/s/{sid}/bots")
async def list_bots(sid: str):
    s = get_server(sid)
    return s.get("bots", [])

@app.post("/s/{sid}/bots")
async def add_bot(
    sid: str,
    name: str = Form(...),
    personality: str = Form(""),
    voice_enabled: bool = Form(True),
    proactive_enabled: bool = Form(True),
    model: str = Form(""),
    system_prompt: str = Form(""),
):
    s = get_server(sid)
    bot = make_default_bot(name, personality)
    bot["voice_enabled"] = voice_enabled
    bot["proactive_enabled"] = proactive_enabled
    bot["model"] = model
    bot["system_prompt"] = system_prompt
    s.setdefault("bots", []).append(bot)
    await save_state()
    return bot

@app.delete("/s/{sid}/bots/{bid}")
async def remove_bot(sid: str, bid: str):
    s = get_server(sid)
    s["bots"] = [b for b in s.get("bots", []) if b["id"] != bid]
    await save_state()
    return {"ok": True}

@app.get("/s/{sid}/knowledge")
async def list_knowledge(sid: str):
    s = get_server(sid)
    return s.get("knowledge_base", [])

@app.post("/s/{sid}/knowledge")
async def add_knowledge(sid: str, title: str = Form(...), content: str = Form(...)):
    s = get_server(sid)
    item = {"id": str(uuid.uuid4()), "title": title, "content": content, "created_at": datetime.utcnow().isoformat()}
    s.setdefault("knowledge_base", []).append(item)
    await save_state()
    return item

@app.delete("/s/{sid}/knowledge/{kid}")
async def remove_knowledge(sid: str, kid: str):
    s = get_server(sid)
    s["knowledge_base"] = [k for k in s.get("knowledge_base", []) if k["id"] != kid]
    await save_state()
    return {"ok": True}

# ---------------------------------------------------------------------------
# Channel API
# ---------------------------------------------------------------------------

@app.post("/s/{sid}/channels")
async def create_channel(
    sid: str,
    name: str = Form(...),
    channel_type: str = Form("text"),
    password: Optional[str] = Form(""),
):
    s = get_server(sid)
    if channel_type == "voice":
        ch = make_voice_channel(name, password)
    else:
        ch = make_text_channel(name, password)
    s["rooms"][ch["id"]] = ch
    await save_state()
    return {"channel_id": ch["id"], "name": name, "type": ch["type"]}

@app.get("/s/{sid}/channels")
async def list_channels(sid: str):
    s = get_server(sid)
    return [
        {
            "id": rid,
            "name": r["name"],
            "type": r.get("type", "text"),
            "has_password": bool(r.get("password")),
            "user_count": len(r.get("users", {})),
        }
        for rid, r in s.get("rooms", {}).items()
    ]

@app.get("/s/{sid}/channels/{cid}")
async def get_channel_info(sid: str, cid: str):
    s = get_server(sid)
    ch = get_channel(s, cid)
    return {
        "id": ch["id"],
        "name": ch["name"],
        "type": ch.get("type", "text"),
        "has_password": bool(ch.get("password")),
        "user_count": len(ch.get("users", {})),
        "ai_enabled": ch.get("ai_enabled", True),
    }

@app.post("/s/{sid}/channels/{cid}/settings")
async def update_channel_settings(
    sid: str, cid: str,
    name: Optional[str] = Form(None),
    ai_enabled: Optional[bool] = Form(None),
):
    s = get_server(sid)
    ch = get_channel(s, cid)
    if name is not None:
        ch["name"] = name
    if ai_enabled is not None:
        ch["ai_enabled"] = ai_enabled
    await save_state()
    return {"ok": True}

@app.delete("/s/{sid}/channels/{cid}")
async def delete_channel(sid: str, cid: str):
    s = get_server(sid)
    if cid not in s.get("rooms", {}):
        raise HTTPException(status_code=404, detail="Channel not found")
    # Clean up photos
    for photo in s["rooms"][cid].get("photos", []):
        pid = photo.get("id", "")
        ppath = static_dir / "photos" / f"{pid}.jpg"
        if ppath.exists():
            ppath.unlink()
    # Clean up connections
    key = f"{sid}:{cid}"
    if key in manager.active:
        for conn in manager.active.pop(key, []):
            try:
                await conn["ws"].close()
            except Exception:
                pass
    del s["rooms"][cid]
    await save_state()
    return {"deleted": cid}

@app.post("/s/{sid}/channels/{cid}/join")
async def join_channel(
    sid: str, cid: str,
    user_id: str = Form(...),
    user_name: str = Form(...),
    password: Optional[str] = Form(""),
):
    s = get_server(sid)
    ch = get_channel(s, cid)
    if ch.get("password") and ch["password"] != password:
        raise HTTPException(status_code=403, detail="Invalid password")
    ch["users"][user_id] = {"user_name": user_name, "joined_at": datetime.utcnow().isoformat()}
    # Track server-level membership
    s.setdefault("members", {})[user_id] = {
        "user_name": user_name,
        "role": "member",
        "joined_at": datetime.utcnow().isoformat(),
    }
    await save_state()
    return {"ok": True, "channel_id": cid, "channel_name": ch["name"]}

@app.post("/s/{sid}/channels/{cid}/messages")
async def send_message(
    sid: str, cid: str,
    sender: str = Form(...),
    content: str = Form(...),
    sender_id: Optional[str] = Form(None),
):
    s = get_server(sid)
    ch = get_channel(s, cid)
    if len(content) > MAX_MESSAGE_LENGTH:
        raise HTTPException(status_code=400, detail="Message too long")
    msg = {
        "id": str(uuid.uuid4()),
        "sender": sender,
        "sender_id": sender_id or "",
        "content": content,
        "type": "user",
        "timestamp": datetime.utcnow().isoformat()
    }
    ch["messages"].append(msg)
    await manager.broadcast(sid, cid, msg)
    # AI reply
    if ch.get("ai_enabled", True):
        for bot in s.get("bots", []):
            allowed = bot.get("channels", [])
            if allowed and cid not in allowed:
                continue
            reply = await generate_agent_reply(s, bot, ch, content, sender)
            if reply:
                rmsg = {
                    "id": str(uuid.uuid4()),
                    "sender": bot["name"],
                    "sender_id": bot["id"],
                    "content": reply,
                    "type": "agent",
                    "timestamp": datetime.utcnow().isoformat()
                }
                ch["messages"].append(rmsg)
                await manager.broadcast(sid, cid, rmsg)
    await save_state()
    return msg

@app.get("/s/{sid}/channels/{cid}/messages")
async def get_messages(sid: str, cid: str, limit: int = 50):
    s = get_server(sid)
    ch = get_channel(s, cid)
    return ch.get("messages", [])[-limit:]

# ---------------------------------------------------------------------------
# Notebook / Tasks / Events / Photos (per channel)
# ---------------------------------------------------------------------------

@app.post("/s/{sid}/channels/{cid}/notes")
async def add_note(
    sid: str, cid: str,
    content: str = Form(...),
    category: str = Form("general"),
    created_by: str = Form(...),
):
    s = get_server(sid)
    ch = get_channel(s, cid)
    note = {
        "id": str(uuid.uuid4()),
        "content": content,
        "category": category,
        "created_by": created_by,
        "created_at": datetime.utcnow().isoformat(),
        "is_pinned": False,
    }
    ch["notes"].insert(0, note)
    await manager.broadcast(sid, cid, {"type": "note_added", "note": note})
    await save_state()
    return note

@app.get("/s/{sid}/channels/{cid}/notes")
async def get_notes(sid: str, cid: str):
    s = get_server(sid)
    ch = get_channel(s, cid)
    pinned = [n for n in ch["notes"] if n.get("is_pinned")]
    others = [n for n in ch["notes"] if not n.get("is_pinned")]
    return pinned + others

@app.post("/s/{sid}/channels/{cid}/notes/{nid}/pin")
async def pin_note(sid: str, cid: str, nid: str):
    s = get_server(sid)
    ch = get_channel(s, cid)
    for n in ch["notes"]:
        if n["id"] == nid:
            n["is_pinned"] = not n.get("is_pinned", False)
            break
    await save_state()
    return {"ok": True}

@app.post("/s/{sid}/channels/{cid}/tasks")
async def add_task(
    sid: str, cid: str,
    title: str = Form(...),
    assignee_user_id: Optional[str] = Form(None),
    assignee_name: Optional[str] = Form(None),
    due_at: Optional[str] = Form(None),
    created_by: str = Form(...),
):
    s = get_server(sid)
    ch = get_channel(s, cid)
    task = {
        "id": str(uuid.uuid4()),
        "title": title,
        "assignee_user_id": assignee_user_id,
        "assignee_name": assignee_name,
        "due_at": due_at,
        "done": False,
        "created_by": created_by,
        "created_at": datetime.utcnow().isoformat(),
    }
    ch["tasks"].insert(0, task)
    await manager.broadcast(sid, cid, {"type": "task_added", "task": task})
    await save_state()
    return task

@app.get("/s/{sid}/channels/{cid}/tasks")
async def get_tasks(sid: str, cid: str):
    s = get_server(sid)
    ch = get_channel(s, cid)
    return ch.get("tasks", [])

@app.post("/s/{sid}/channels/{cid}/tasks/{tid}/done")
async def mark_task_done(sid: str, cid: str, tid: str):
    s = get_server(sid)
    ch = get_channel(s, cid)
    for t in ch["tasks"]:
        if t["id"] == tid:
            t["done"] = True
            break
    await save_state()
    return {"ok": True}

@app.post("/s/{sid}/channels/{cid}/events")
async def add_event(
    sid: str, cid: str,
    title: str = Form(...),
    event_at: str = Form(...),
    created_by: str = Form(...),
):
    s = get_server(sid)
    ch = get_channel(s, cid)
    event = {
        "id": str(uuid.uuid4()),
        "title": title,
        "event_at": event_at,
        "created_by": created_by,
        "created_at": datetime.utcnow().isoformat(),
    }
    ch["events"].insert(0, event)
    await manager.broadcast(sid, cid, {"type": "event_added", "event": event})
    await save_state()
    return event

@app.get("/s/{sid}/channels/{cid}/events")
async def get_events(sid: str, cid: str):
    s = get_server(sid)
    ch = get_channel(s, cid)
    return ch.get("events", [])

@app.post("/s/{sid}/channels/{cid}/photos")
async def upload_photo(
    sid: str, cid: str,
    file: UploadFile = File(...),
    uploaded_by: str = Form(...),
    uploaded_by_name: Optional[str] = Form(None),
):
    s = get_server(sid)
    ch = get_channel(s, cid)
    # File size check
    contents = await file.read()
    if len(contents) > MAX_FILE_SIZE:
        raise HTTPException(status_code=413, detail="File too large (max 10MB)")
    fid = secrets.token_hex(8)
    dest = static_dir / "photos" / f"{fid}.jpg"
    with open(dest, "wb") as f:
        f.write(contents)
    photo = {
        "id": fid,
        "url": f"/static/photos/{fid}.jpg",
        "uploaded_by": uploaded_by,
        "uploaded_by_name": uploaded_by_name or uploaded_by,
        "created_at": datetime.utcnow().isoformat(),
    }
    ch["photos"].append(photo)
    # AI comment
    for bot in s.get("bots", [])[:1]:
        comment = await generate_photo_comment(s, bot, uploaded_by_name or uploaded_by)
        if comment:
            rmsg = {
                "id": str(uuid.uuid4()),
                "sender": bot["name"],
                "sender_id": bot["id"],
                "content": comment,
                "type": "agent",
                "timestamp": datetime.utcnow().isoformat()
            }
            ch["messages"].append(rmsg)
            await manager.broadcast(sid, cid, rmsg)
    await manager.broadcast(sid, cid, {"type": "photo_added", "photo": photo})
    await save_state()
    return photo

@app.get("/s/{sid}/channels/{cid}/photos")
async def get_photos(sid: str, cid: str):
    s = get_server(sid)
    ch = get_channel(s, cid)
    return ch.get("photos", [])

@app.post("/s/{sid}/channels/{cid}/agents")
async def add_agent(
    sid: str, cid: str,
    name: str = Form(...),
    personality: str = Form(""),
    voice_enabled: bool = Form(True),
    proactive_enabled: bool = Form(True),
):
    s = get_server(sid)
    ch = get_channel(s, cid)
    agent = {
        "id": str(uuid.uuid4()),
        "name": name,
        "personality": personality,
        "voice_enabled": voice_enabled,
        "proactive_enabled": proactive_enabled,
    }
    ch.setdefault("agents", []).append(agent)
    await save_state()
    return agent

@app.get("/s/{sid}/channels/{cid}/agents")
async def get_agents(sid: str, cid: str):
    s = get_server(sid)
    ch = get_channel(s, cid)
    return ch.get("agents", [])

@app.post("/s/{sid}/channels/{cid}/whisper")
async def send_whisper(
    sid: str, cid: str,
    from_user_id: str = Form(...),
    from_user_name: str = Form(...),
    to_user_id: str = Form(...),
    content: str = Form(...),
):
    s = get_server(sid)
    ch = get_channel(s, cid)
    msg = {
        "type": "whisper",
        "from_user_id": from_user_id,
        "from_user_name": from_user_name,
        "to_user_id": to_user_id,
        "content": content,
        "timestamp": datetime.utcnow().isoformat()
    }
    await manager.send_to(sid, cid, to_user_id, msg)
    await manager.send_to(sid, cid, from_user_id, msg)
    return {"ok": True}

@app.post("/s/{sid}/channels/{cid}/nudge")
async def send_nudge(
    sid: str, cid: str,
    from_user_id: str = Form(...),
    from_user_name: str = Form(...),
    to_user_id: str = Form(...),
):
    s = get_server(sid)
    await manager.send_to(sid, cid, to_user_id, {
        "type": "nudge",
        "from_user_id": from_user_id,
        "from_user_name": from_user_name,
        "to_user_id": to_user_id,
        "timestamp": datetime.utcnow().isoformat()
    })
    return {"ok": True}

# ---------------------------------------------------------------------------
# Avatar upload
# ---------------------------------------------------------------------------

@app.post("/upload/avatar")
async def upload_avatar(file: UploadFile = File(...)):
    contents = await file.read()
    if len(contents) > MAX_FILE_SIZE:
        raise HTTPException(status_code=413, detail="File too large (max 10MB)")
    fid = secrets.token_hex(8)
    ext = Path(file.filename or "avatar.vrm").suffix.lower()
    if ext not in (".vrm", ".glb", ".gltf"):
        ext = ".vrm"
    dest = static_dir / "avatars" / f"{fid}{ext}"
    with open(dest, "wb") as f:
        f.write(contents)
    return {"url": f"/static/avatars/{fid}{ext}", "filename": f"{fid}{ext}"}

# ---------------------------------------------------------------------------
# Ollama endpoints
# ---------------------------------------------------------------------------

@app.post("/s/{sid}/ollama/generate")
async def ollama_generate_endpoint(sid: str, prompt: str = Form(...)):
    s = get_server(sid)
    if len(prompt) > MAX_OLLAMA_PROMPT_LENGTH:
        prompt = prompt[:MAX_OLLAMA_PROMPT_LENGTH]
    res = await ollama_generate(s, prompt)
    if res is None:
        return JSONResponse({"error": "Ollama not configured or unreachable"}, status_code=503)
    return {"response": res.strip()}

@app.post("/s/{sid}/summarize")
async def summarize_endpoint(sid: str, context: str = Form(...)):
    s = get_server(sid)
    if len(context) > MAX_OLLAMA_PROMPT_LENGTH:
        context = context[:MAX_OLLAMA_PROMPT_LENGTH]
    prompt = f"以下のメモを要約してください:\n{context}\n\n要約:"
    res = await ollama_generate(s, prompt)
    if res is None:
        return JSONResponse({"error": "Ollama not configured or unreachable"}, status_code=503)
    return {"summary": res.strip()}

# ---------------------------------------------------------------------------
# WebSocket
# ---------------------------------------------------------------------------

@app.websocket("/ws/{sid}/{rid}")
async def websocket_endpoint(ws: WebSocket, sid: str, rid: str):
    user_id = None
    user_name = None
    joined = False
    room = None
    try:
        await ws.accept()
        s = get_server(sid)
        room = s.get("rooms", {}).get(rid)
        if not room:
            await ws.send_json({"type": "error", "message": "Channel not found"})
            await ws.close()
            return

        raw = await ws.receive_text()
        try:
            payload = json.loads(raw)
            if payload.get("type") == "join":
                user_id = payload.get("user_id", str(uuid.uuid4()))
                user_name = payload.get("user_name", "匿名")
                password = payload.get("password", "")
                if room.get("password") and room["password"] != password:
                    await ws.send_json({"type": "error", "message": "Invalid password"})
                    await ws.close()
                    return
                room["users"][user_id] = {
                    "user_name": user_name,
                    "joined_at": datetime.utcnow().isoformat()
                }
                await manager.connect(sid, rid, ws, user_id, user_name)
                joined = True
                await ws.send_json({
                    "type": "joined",
                    "server_id": sid,
                    "room_id": rid,
                    "room_name": room["name"],
                    "user_id": user_id,
                    "users": manager.get_users(sid, rid)
                })
                await manager.broadcast(sid, rid, {
                    "type": "user_joined",
                    "user_id": user_id,
                    "user_name": user_name,
                    "users": manager.get_users(sid, rid)
                }, exclude_ws=ws)
                # Proactive greeting
                if room.get("type", "text") == "text":
                    for bot in s.get("bots", [])[:1]:
                        if bot.get("proactive_enabled", True):
                            greeting = await generate_briefing(s, room, user_name)
                            if greeting:
                                pmsg = {
                                    "id": str(uuid.uuid4()),
                                    "sender": bot["name"],
                                    "sender_id": bot["id"],
                                    "content": greeting,
                                    "type": "proactive",
                                    "timestamp": datetime.utcnow().isoformat()
                                }
                                room["messages"].append(pmsg)
                                await manager.broadcast(sid, rid, pmsg)
                await save_state()
            else:
                await ws.send_json({"type": "error", "message": "Expected join message"})
                await ws.close()
                return
        except json.JSONDecodeError:
            await ws.send_json({"type": "error", "message": "Invalid JSON"})
            await ws.close()
            return

        while True:
            data = await ws.receive_text()
            try:
                payload = json.loads(data)
                msg_type = payload.get("type", "message")
                if msg_type == "message":
                    content = payload.get("content", "")
                    if len(content) > MAX_MESSAGE_LENGTH:
                        await ws.send_json({"type": "error", "message": "Message too long"})
                        continue
                    msg = {
                        "id": str(uuid.uuid4()),
                        "sender": payload.get("sender", user_name),
                        "sender_id": payload.get("sender_id", user_id),
                        "content": content,
                        "type": "user",
                        "timestamp": datetime.utcnow().isoformat()
                    }
                    room["messages"].append(msg)
                    await manager.broadcast(sid, rid, msg, exclude_ws=ws)
                    if room.get("type", "text") == "text" and room.get("ai_enabled", True):
                        for bot in s.get("bots", []):
                            allowed = bot.get("channels", [])
                            if allowed and rid not in allowed:
                                continue
                            reply = await generate_agent_reply(s, bot, room, content, msg["sender"])
                            if reply:
                                rmsg = {
                                    "id": str(uuid.uuid4()),
                                    "sender": bot["name"],
                                    "sender_id": bot["id"],
                                    "content": reply,
                                    "type": "agent",
                                    "timestamp": datetime.utcnow().isoformat()
                                }
                                room["messages"].append(rmsg)
                                await manager.broadcast(sid, rid, rmsg)
                    await save_state()
                elif msg_type == "reaction":
                    reaction = {
                        "type": "reaction",
                        "message_id": payload.get("message_id"),
                        "emoji": payload.get("emoji"),
                        "from_user_id": user_id,
                        "from_user_name": user_name,
                        "timestamp": datetime.utcnow().isoformat()
                    }
                    await manager.broadcast(sid, rid, reaction)
                elif msg_type == "ping":
                    await ws.send_json({"type": "pong"})
            except json.JSONDecodeError:
                pass
    except WebSocketDisconnect:
        pass
    finally:
        if joined:
            manager.disconnect(sid, rid, ws)
            if room and user_id:
                room["users"].pop(user_id, None)
            await manager.broadcast(sid, rid, {
                "type": "user_left",
                "user_id": user_id,
                "user_name": user_name,
                "users": manager.get_users(sid, rid)
            })
            await save_state()

# ---------------------------------------------------------------------------
# WebRTC Voice Signaling
# ---------------------------------------------------------------------------

# In-memory state for voice signaling: key = f"{sid}:{cid}", value = dict of user_id -> {offer, answer, ice_candidates}
voice_sessions: Dict[str, Dict[str, dict]] = {}

@app.post("/s/{sid}/channels/{cid}/voice/join")
async def voice_join(sid: str, cid: str, user_id: str = Form(...), user_name: str = Form(...)):
    s = get_server(sid)
    ch = get_channel(s, cid)
    if ch.get("type") != "voice":
        raise HTTPException(status_code=400, detail="Not a voice channel")
    key = f"{sid}:{cid}"
    if key not in voice_sessions:
        voice_sessions[key] = {}
    voice_sessions[key][user_id] = {"user_name": user_name, "offer": None, "answer": None, "candidates": []}
    # Notify others in the channel
    await manager.broadcast(sid, cid, {
        "type": "voice_user_joined",
        "user_id": user_id,
        "user_name": user_name,
    })
    return {"users": [{"user_id": uid, "user_name": info["user_name"]} for uid, info in voice_sessions.get(key, {}).items()]}

@app.post("/s/{sid}/channels/{cid}/voice/leave")
async def voice_leave(sid: str, cid: str, user_id: str = Form(...)):
    key = f"{sid}:{cid}"
    if key in voice_sessions:
        voice_sessions[key].pop(user_id, None)
        if not voice_sessions[key]:
            del voice_sessions[key]
    await manager.broadcast(sid, cid, {
        "type": "voice_user_left",
        "user_id": user_id,
    })
    return {"ok": True}

@app.post("/s/{sid}/channels/{cid}/voice/offer")
async def voice_offer(sid: str, cid: str, from_user_id: str = Form(...), to_user_id: str = Form(...), sdp: str = Form(...)):
    await manager.send_to(sid, cid, to_user_id, {
        "type": "voice_offer",
        "from_user_id": from_user_id,
        "to_user_id": to_user_id,
        "sdp": sdp,
    })
    return {"ok": True}

@app.post("/s/{sid}/channels/{cid}/voice/answer")
async def voice_answer(sid: str, cid: str, from_user_id: str = Form(...), to_user_id: str = Form(...), sdp: str = Form(...)):
    await manager.send_to(sid, cid, to_user_id, {
        "type": "voice_answer",
        "from_user_id": from_user_id,
        "to_user_id": to_user_id,
        "sdp": sdp,
    })
    return {"ok": True}

@app.post("/s/{sid}/channels/{cid}/voice/ice")
async def voice_ice(sid: str, cid: str, from_user_id: str = Form(...), to_user_id: str = Form(...), candidate: str = Form(...), sdp_mline_index: int = Form(...), sdp_mid: str = Form(...)):
    await manager.send_to(sid, cid, to_user_id, {
        "type": "voice_ice",
        "from_user_id": from_user_id,
        "to_user_id": to_user_id,
        "candidate": candidate,
        "sdpMLineIndex": sdp_mline_index,
        "sdpMid": sdp_mid,
    })
    return {"ok": True}

# ---------------------------------------------------------------------------
# Backward compatibility aliases (default server)
# ---------------------------------------------------------------------------

@app.post("/rooms")
async def compat_create_room(name: str = Form(...), password: Optional[str] = Form("")):
    return await create_channel("default", name, "text", password)

@app.get("/rooms")
async def compat_list_rooms():
    return await list_channels("default")

@app.get("/rooms/{rid}")
async def compat_get_room(rid: str):
    return await get_channel_info("default", rid)

@app.post("/rooms/{rid}/join")
async def compat_join_room(rid: str, user_id: str = Form(...), user_name: str = Form(...), password: Optional[str] = Form("")):
    return await join_channel("default", rid, user_id, user_name, password)

@app.post("/rooms/{rid}/messages")
async def compat_send_message(rid: str, sender: str = Form(...), content: str = Form(...), sender_id: Optional[str] = Form(None)):
    return await send_message("default", rid, sender, content, sender_id)

@app.get("/rooms/{rid}/messages")
async def compat_get_messages(rid: str, limit: int = 50):
    return await get_messages("default", rid, limit)

@app.post("/rooms/{rid}/notes")
async def compat_add_note(rid: str, content: str = Form(...), category: str = Form("general"), created_by: str = Form(...), due_at: Optional[str] = Form(None)):
    return await add_note("default", rid, content, category, created_by)

@app.get("/rooms/{rid}/notes")
async def compat_get_notes(rid: str):
    return await get_notes("default", rid)

@app.post("/rooms/{rid}/notes/{nid}/pin")
async def compat_pin_note(rid: str, nid: str):
    return await pin_note("default", rid, nid)

@app.post("/rooms/{rid}/tasks")
async def compat_add_task(rid: str, title: str = Form(...), assignee_user_id: Optional[str] = Form(None), assignee_name: Optional[str] = Form(None), due_at: Optional[str] = Form(None), created_by: str = Form(...)):
    return await add_task("default", rid, title, assignee_user_id, assignee_name, due_at, created_by)

@app.get("/rooms/{rid}/tasks")
async def compat_get_tasks(rid: str):
    return await get_tasks("default", rid)

@app.post("/rooms/{rid}/tasks/{tid}/done")
async def compat_done_task(rid: str, tid: str):
    return await mark_task_done("default", rid, tid)

@app.post("/rooms/{rid}/events")
async def compat_add_event(rid: str, title: str = Form(...), event_at: str = Form(...), created_by: str = Form(...)):
    return await add_event("default", rid, title, event_at, created_by)

@app.get("/rooms/{rid}/events")
async def compat_get_events(rid: str):
    return await get_events("default", rid)

@app.post("/rooms/{rid}/photos")
async def compat_upload_photo(rid: str, file: UploadFile = File(...), uploaded_by: str = Form(...), uploaded_by_name: Optional[str] = Form(None)):
    return await upload_photo("default", rid, file, uploaded_by, uploaded_by_name)

@app.get("/rooms/{rid}/photos")
async def compat_get_photos(rid: str):
    return await get_photos("default", rid)

@app.post("/rooms/{rid}/agents")
async def compat_add_agent(rid: str, name: str = Form(...), personality: str = Form(""), voice_enabled: bool = Form(True), proactive_enabled: bool = Form(True)):
    return await add_agent("default", rid, name, personality, voice_enabled, proactive_enabled)

@app.get("/rooms/{rid}/agents")
async def compat_get_agents(rid: str):
    return await get_agents("default", rid)

@app.post("/rooms/{rid}/whisper")
async def compat_whisper(rid: str, from_user_id: str = Form(...), from_user_name: str = Form(...), to_user_id: str = Form(...), content: str = Form(...)):
    return await send_whisper("default", rid, from_user_id, from_user_name, to_user_id, content)

@app.post("/rooms/{rid}/nudge")
async def compat_nudge(rid: str, from_user_id: str = Form(...), from_user_name: str = Form(...), to_user_id: str = Form(...)):
    return await send_nudge("default", rid, from_user_id, from_user_name, to_user_id)

@app.websocket("/ws/{rid}")
async def compat_ws(ws: WebSocket, rid: str):
    return await websocket_endpoint(ws, "default", rid)

# ---------------------------------------------------------------------------
# Root
# ---------------------------------------------------------------------------

@app.get("/", response_class=HTMLResponse)
async def root():
    if _web_html:
        return _web_html
    return "<h1>FamiChibi Server</h1><p>Server is running.</p>"

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
