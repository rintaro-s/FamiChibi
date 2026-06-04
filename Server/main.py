import json
import os
import re
import shutil
import socket
import threading
import uuid
import asyncio
from datetime import datetime, timedelta
from pathlib import Path
from typing import Dict, List, Optional

from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Form, Request, Query, UploadFile, File
from fastapi.responses import HTMLResponse, JSONResponse, FileResponse
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware

app = FastAPI(title="FamiChibi Chat Server")
BASE_DIR = Path(__file__).resolve().parent
WORKSPACE_ROOT = BASE_DIR.parent

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# In-memory storage
rooms: Dict[str, Dict] = {}

# UDP discovery listener
def start_udp_listener():
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.bind(("0.0.0.0", 8001))
        print("UDP discovery listener started on port 8001")
        while True:
            data, addr = sock.recvfrom(1024)
            try:
                msg = data.decode("utf-8")
                if msg == "FamiChibi-discover":
                    response = json.dumps({
                        "name": "FamiChibi Server",
                        "version": "2.0.0",
                        "port": 8000,
                        "features": [
                            "rooms", "password", "avatars", "websocket",
                            "notebook", "tasks", "events", "photos",
                            "whisper", "nudge", "reactions", "agent_memory"
                        ]
                    })
                    sock.sendto(response.encode("utf-8"), addr)
            except Exception:
                pass
    except Exception as e:
        print(f"UDP listener error: {e}")

udp_thread = threading.Thread(target=start_udp_listener, daemon=True)
udp_thread.start()

class ConnectionManager:
    def __init__(self):
        self.active_connections: Dict[str, List[dict]] = {}

    async def connect(self, room_id: str, websocket: WebSocket, user_id: str, user_name: str):
        if room_id not in self.active_connections:
            self.active_connections[room_id] = []
        self.active_connections[room_id].append({
            "ws": websocket,
            "user_id": user_id,
            "user_name": user_name
        })

    def disconnect(self, room_id: str, websocket: WebSocket):
        if room_id in self.active_connections:
            for conn in list(self.active_connections[room_id]):
                if conn["ws"] == websocket:
                    self.active_connections[room_id].remove(conn)
                    break
            if not self.active_connections[room_id]:
                del self.active_connections[room_id]

    def get_users(self, room_id: str) -> List[dict]:
        if room_id not in self.active_connections:
            return []
        return [{"user_id": c["user_id"], "user_name": c["user_name"]} for c in self.active_connections[room_id]]

    def get_conn_for_user(self, room_id: str, user_id: str) -> Optional[WebSocket]:
        if room_id not in self.active_connections:
            return None
        for conn in self.active_connections[room_id]:
            if conn["user_id"] == user_id:
                return conn["ws"]
        return None

    async def broadcast(self, room_id: str, message: dict, exclude_ws: Optional[WebSocket] = None):
        if room_id not in self.active_connections:
            return
        for conn in list(self.active_connections[room_id]):
            if exclude_ws and conn["ws"] == exclude_ws:
                continue
            try:
                await conn["ws"].send_json(message)
            except Exception:
                self.disconnect(room_id, conn["ws"])

    async def send_to(self, room_id: str, user_id: str, message: dict):
        ws = self.get_conn_for_user(room_id, user_id)
        if ws is None:
            return
        try:
            await ws.send_json(message)
        except Exception:
            pass

manager = ConnectionManager()

# Ensure directories exist
static_dir = BASE_DIR / "static"
if not static_dir.exists():
    static_dir = WORKSPACE_ROOT / "static"

template_dir = BASE_DIR / "templates"
if not template_dir.exists():
    template_dir = WORKSPACE_ROOT / "templates"

os.makedirs(static_dir, exist_ok=True)
os.makedirs(template_dir, exist_ok=True)
os.makedirs(static_dir / "avatars", exist_ok=True)
os.makedirs(static_dir / "photos", exist_ok=True)

app.mount("/static", StaticFiles(directory=str(static_dir)), name="static")

# Load chat.html content at startup
_chat_html = ""
try:
    with open(template_dir / "chat.html", "r", encoding="utf-8") as f:
        _chat_html = f.read()
except Exception:
    pass


@app.get("/discover")
async def discover():
    return {
        "name": "FamiChibi Server",
        "version": "2.0.0",
        "port": 8000,
        "features": [
            "rooms", "password", "avatars", "websocket",
            "notebook", "tasks", "events", "photos",
            "whisper", "nudge", "reactions", "agent_memory"
        ]
    }


@app.get("/", response_class=HTMLResponse)
async def chat_ui():
    return _chat_html if _chat_html else "<h1>FamiChibi Chat Server</h1><p>Server is running.</p>"


@app.post("/rooms")
async def create_room(
    name: str = Form(...),
    password: Optional[str] = Form("")
):
    room_id = str(uuid.uuid4())[:8]
    rooms[room_id] = {
        "id": room_id,
        "name": name,
        "password": password,
        "created_at": datetime.utcnow().isoformat(),
        "messages": [],
        "agents": [{
            "id": str(uuid.uuid4()),
            "name": "ファミちび",
            "personality": "やさしい",
            "mood": 0,
            "voice_enabled": True,
            "proactive_enabled": True
        }],
        "users": {},
        "notes": [],
        "tasks": [],
        "events": [],
        "photos": []
    }
    return {"room_id": room_id, "name": name, "has_password": bool(password)}


@app.get("/rooms")
async def list_rooms():
    result = []
    for room_id, room in rooms.items():
        result.append({
            "id": room_id,
            "name": room["name"],
            "has_password": bool(room.get("password")),
            "user_count": len(manager.get_users(room_id)),
            "created_at": room.get("created_at", "")
        })
    return sorted(result, key=lambda x: x["created_at"], reverse=True)


@app.get("/rooms/{room_id}")
async def get_room(room_id: str):
    if room_id not in rooms:
        return JSONResponse({"error": "Room not found"}, status_code=404)
    room = rooms[room_id]
    return {
        "id": room_id,
        "name": room["name"],
        "has_password": bool(room.get("password")),
        "user_count": len(manager.get_users(room_id)),
        "users": manager.get_users(room_id)
    }


@app.post("/rooms/{room_id}/join")
async def join_room(
    room_id: str,
    user_id: str = Form(...),
    user_name: str = Form(...),
    password: Optional[str] = Form("")
):
    if room_id not in rooms:
        return JSONResponse({"error": "Room not found"}, status_code=404)
    room = rooms[room_id]
    if room.get("password") and room["password"] != password:
        return JSONResponse({"error": "Invalid password"}, status_code=403)
    room["users"][user_id] = {
        "user_name": user_name,
        "joined_at": datetime.utcnow().isoformat()
    }
    return {"success": True, "room_name": room["name"]}


# ---------- Messages ----------

@app.post("/rooms/{room_id}/messages")
async def send_message(room_id: str, sender: str = Form(...), content: str = Form(...), sender_id: Optional[str] = Form(None)):
    if room_id not in rooms:
        return JSONResponse({"error": "Room not found"}, status_code=404)

    message = {
        "id": str(uuid.uuid4()),
        "sender": sender,
        "sender_id": sender_id or "",
        "content": content,
        "type": "user",
        "timestamp": datetime.utcnow().isoformat()
    }
    rooms[room_id]["messages"].append(message)
    await manager.broadcast(room_id, message)

    for agent in rooms[room_id].get("agents", []):
        agent_reply = await generate_agent_reply(agent, rooms[room_id], content, sender)
        if agent_reply:
            reply_msg = {
                "id": str(uuid.uuid4()),
                "sender": agent["name"],
                "sender_id": agent["id"],
                "content": agent_reply,
                "type": "agent",
                "timestamp": datetime.utcnow().isoformat()
            }
            rooms[room_id]["messages"].append(reply_msg)
            await manager.broadcast(room_id, reply_msg)

    return message


@app.get("/rooms/{room_id}/messages")
async def get_messages(room_id: str, limit: int = 50):
    if room_id not in rooms:
        return []
    return rooms[room_id]["messages"][-limit:]


# ---------- Notebook ----------

@app.post("/rooms/{room_id}/notes")
async def add_note(
    room_id: str,
    content: str = Form(...),
    category: str = Form("general"),
    created_by: str = Form(...),
    due_at: Optional[str] = Form(None)
):
    if room_id not in rooms:
        return JSONResponse({"error": "Room not found"}, status_code=404)
    note = {
        "id": str(uuid.uuid4()),
        "content": content,
        "category": category,
        "created_by": created_by,
        "created_at": datetime.utcnow().isoformat(),
        "due_at": due_at or None,
        "is_pinned": False
    }
    rooms[room_id]["notes"].append(note)
    await manager.broadcast(room_id, {"type": "note_added", "note": note})
    return note


@app.get("/rooms/{room_id}/notes")
async def get_notes(room_id: str):
    if room_id not in rooms:
        return []
    return sorted(rooms[room_id]["notes"], key=lambda n: (not n.get("is_pinned"), n.get("created_at", "")), reverse=True)


@app.post("/rooms/{room_id}/notes/{note_id}/pin")
async def pin_note(room_id: str, note_id: str):
    if room_id not in rooms:
        return JSONResponse({"error": "Room not found"}, status_code=404)
    for note in rooms[room_id]["notes"]:
        if note["id"] == note_id:
            note["is_pinned"] = not note.get("is_pinned", False)
            return note
    return JSONResponse({"error": "Note not found"}, status_code=404)


# ---------- Tasks ----------

@app.post("/rooms/{room_id}/tasks")
async def add_task(
    room_id: str,
    title: str = Form(...),
    assignee_user_id: Optional[str] = Form(None),
    assignee_name: Optional[str] = Form(None),
    due_at: Optional[str] = Form(None),
    created_by: str = Form(...)
):
    if room_id not in rooms:
        return JSONResponse({"error": "Room not found"}, status_code=404)
    task = {
        "id": str(uuid.uuid4()),
        "title": title,
        "assignee_user_id": assignee_user_id,
        "assignee_name": assignee_name,
        "due_at": due_at or None,
        "done": False,
        "created_by": created_by,
        "created_at": datetime.utcnow().isoformat()
    }
    rooms[room_id]["tasks"].append(task)
    await manager.broadcast(room_id, {"type": "task_added", "task": task})
    return task


@app.get("/rooms/{room_id}/tasks")
async def get_tasks(room_id: str):
    if room_id not in rooms:
        return []
    return rooms[room_id]["tasks"]


@app.post("/rooms/{room_id}/tasks/{task_id}/done")
async def mark_task_done(room_id: str, task_id: str):
    if room_id not in rooms:
        return JSONResponse({"error": "Room not found"}, status_code=404)
    for task in rooms[room_id]["tasks"]:
        if task["id"] == task_id:
            task["done"] = True
            return task
    return JSONResponse({"error": "Task not found"}, status_code=404)


# ---------- Calendar events ----------

@app.post("/rooms/{room_id}/events")
async def add_event(
    room_id: str,
    title: str = Form(...),
    event_at: str = Form(...),
    created_by: str = Form(...)
):
    if room_id not in rooms:
        return JSONResponse({"error": "Room not found"}, status_code=404)
    event = {
        "id": str(uuid.uuid4()),
        "title": title,
        "event_at": event_at,
        "created_by": created_by,
        "created_at": datetime.utcnow().isoformat()
    }
    rooms[room_id]["events"].append(event)
    await manager.broadcast(room_id, {"type": "event_added", "event": event})
    return event


@app.get("/rooms/{room_id}/events")
async def get_events(room_id: str):
    if room_id not in rooms:
        return []
    return sorted(rooms[room_id]["events"], key=lambda e: e.get("event_at", ""))


# ---------- Photos ----------

@app.post("/rooms/{room_id}/photos")
async def upload_photo(room_id: str, file: UploadFile = File(...), uploaded_by: str = Form(...), uploaded_by_name: Optional[str] = Form(None)):
    if room_id not in rooms:
        return JSONResponse({"error": "Room not found"}, status_code=404)
    ext = Path(file.filename or "image.jpg").suffix
    if ext.lower() not in {".jpg", ".jpeg", ".png", ".webp", ".gif"}:
        ext = ".jpg"
    file_id = f"{uuid.uuid4().hex}{ext}"
    dest = static_dir / "photos" / file_id
    try:
        with open(dest, "wb") as out:
            shutil.copyfileobj(file.file, out)
    except Exception as e:
        return JSONResponse({"error": str(e)}, status_code=500)
    photo = {
        "id": file_id,
        "url": f"/static/photos/{file_id}",
        "uploaded_by": uploaded_by,
        "uploaded_by_name": uploaded_by_name or uploaded_by,
        "uploaded_at": datetime.utcnow().isoformat()
    }
    rooms[room_id]["photos"].append(photo)
    await manager.broadcast(room_id, {"type": "photo_added", "photo": photo})
    for agent in rooms[room_id].get("agents", []):
        comment = await generate_photo_comment(agent, uploaded_by_name or uploaded_by)
        if comment:
            reply_msg = {
                "id": str(uuid.uuid4()),
                "sender": agent["name"],
                "sender_id": agent["id"],
                "content": comment,
                "type": "agent",
                "timestamp": datetime.utcnow().isoformat()
            }
            rooms[room_id]["messages"].append(reply_msg)
            await manager.broadcast(room_id, reply_msg)
    return photo


@app.get("/rooms/{room_id}/photos")
async def get_photos(room_id: str):
    if room_id not in rooms:
        return []
    return rooms[room_id]["photos"]


# ---------- Avatar upload ----------

@app.post("/upload/avatar")
async def upload_avatar(file: UploadFile = File(...)):
    ext = Path(file.filename or "avatar.vrm").suffix
    if ext.lower() != ".vrm":
        ext = ".vrm"
    file_id = f"{uuid.uuid4().hex}{ext}"
    dest = static_dir / "avatars" / file_id
    try:
        with open(dest, "wb") as out:
            shutil.copyfileobj(file.file, out)
    except Exception as e:
        return JSONResponse({"error": str(e)}, status_code=500)
    return {"id": file_id, "url": f"/static/avatars/{file_id}", "name": file.filename or file_id}


@app.get("/static/avatars/{file_id}")
async def serve_avatar(file_id: str):
    dest = static_dir / "avatars" / file_id
    if dest.exists():
        return FileResponse(str(dest))
    return JSONResponse({"error": "Not found"}, status_code=404)


# ---------- Whisper & Nudge ----------

@app.post("/rooms/{room_id}/whisper")
async def send_whisper(room_id: str, from_user_id: str = Form(...), from_user_name: str = Form(...), to_user_id: str = Form(...), content: str = Form(...)):
    if room_id not in rooms:
        return JSONResponse({"error": "Room not found"}, status_code=404)
    payload = {
        "type": "whisper",
        "from_user_id": from_user_id,
        "from_user_name": from_user_name,
        "to_user_id": to_user_id,
        "content": content,
        "timestamp": datetime.utcnow().isoformat()
    }
    await manager.send_to(room_id, to_user_id, payload)
    await manager.send_to(room_id, from_user_id, {**payload, "type": "whisper_sent"})
    return {"success": True}


@app.post("/rooms/{room_id}/nudge")
async def send_nudge(room_id: str, from_user_id: str = Form(...), from_user_name: str = Form(...), to_user_id: str = Form(...)):
    if room_id not in rooms:
        return JSONResponse({"error": "Room not found"}, status_code=404)
    payload = {
        "type": "nudge",
        "from_user_id": from_user_id,
        "from_user_name": from_user_name,
        "to_user_id": to_user_id,
        "timestamp": datetime.utcnow().isoformat()
    }
    await manager.send_to(room_id, to_user_id, payload)
    return {"success": True}


# ---------- Agents ----------

@app.post("/rooms/{room_id}/agents")
async def add_agent(room_id: str, name: str = Form(...), personality: str = Form(""), voice_enabled: bool = Form(True), proactive_enabled: bool = Form(True)):
    if room_id not in rooms:
        return JSONResponse({"error": "Room not found"}, status_code=404)
    agent = {
        "id": str(uuid.uuid4()),
        "name": name,
        "personality": personality,
        "mood": 0,
        "voice_enabled": voice_enabled,
        "proactive_enabled": proactive_enabled
    }
    rooms[room_id]["agents"].append(agent)
    return agent


@app.get("/rooms/{room_id}/agents")
async def get_agents(room_id: str):
    if room_id not in rooms:
        return []
    return rooms[room_id].get("agents", [])


# ---------- WebSocket ----------

@app.websocket("/ws/{room_id}")
async def websocket_endpoint(websocket: WebSocket, room_id: str):
    user_id = None
    user_name = None
    joined = False

    try:
        await websocket.accept()

        raw = await websocket.receive_text()
        try:
            payload = json.loads(raw)
            if payload.get("type") == "join":
                user_id = payload.get("user_id", str(uuid.uuid4()))
                user_name = payload.get("user_name", "匿名")
                password = payload.get("password", "")

                if room_id not in rooms:
                    await websocket.send_json({"type": "error", "message": "Room not found"})
                    await websocket.close()
                    return

                room = rooms[room_id]
                if room.get("password") and room["password"] != password:
                    await websocket.send_json({"type": "error", "message": "Invalid password"})
                    await websocket.close()
                    return

                room["users"][user_id] = {
                    "user_name": user_name,
                    "joined_at": datetime.utcnow().isoformat()
                }
                await manager.connect(room_id, websocket, user_id, user_name)
                joined = True

                await websocket.send_json({
                    "type": "joined",
                    "room_id": room_id,
                    "room_name": room["name"],
                    "user_id": user_id,
                    "users": manager.get_users(room_id)
                })

                await manager.broadcast(room_id, {
                    "type": "user_joined",
                    "user_id": user_id,
                    "user_name": user_name,
                    "users": manager.get_users(room_id)
                }, exclude_ws=websocket)

                # Proactive daily greeting from the first agent
                for agent in room.get("agents", [])[:1]:
                    if agent.get("proactive_enabled", True):
                        greeting = await generate_briefing(agent, room, user_name)
                        if greeting:
                            proactive_msg = {
                                "id": str(uuid.uuid4()),
                                "sender": agent["name"],
                                "sender_id": agent["id"],
                                "content": greeting,
                                "type": "proactive",
                                "timestamp": datetime.utcnow().isoformat()
                            }
                            room["messages"].append(proactive_msg)
                            await manager.broadcast(room_id, proactive_msg)
            else:
                await websocket.send_json({"type": "error", "message": "Expected join message"})
                await websocket.close()
                return
        except json.JSONDecodeError:
            await websocket.send_json({"type": "error", "message": "Invalid JSON"})
            await websocket.close()
            return

        while True:
            data = await websocket.receive_text()
            try:
                payload = json.loads(data)
                msg_type = payload.get("type", "message")

                if msg_type == "message":
                    message = {
                        "id": str(uuid.uuid4()),
                        "sender": payload.get("sender", user_name),
                        "sender_id": user_id,
                        "content": payload.get("content", ""),
                        "type": "user",
                        "timestamp": datetime.utcnow().isoformat()
                    }
                    if room_id in rooms:
                        rooms[room_id]["messages"].append(message)
                        await manager.broadcast(room_id, message, exclude_ws=websocket)

                        for agent in rooms[room_id].get("agents", []):
                            agent_reply = await generate_agent_reply(agent, rooms[room_id], message["content"], message["sender"])
                            if agent_reply:
                                reply_msg = {
                                    "id": str(uuid.uuid4()),
                                    "sender": agent["name"],
                                    "sender_id": agent["id"],
                                    "content": agent_reply,
                                    "type": "agent",
                                    "timestamp": datetime.utcnow().isoformat()
                                }
                                rooms[room_id]["messages"].append(reply_msg)
                                await manager.broadcast(room_id, reply_msg)

                elif msg_type == "reaction":
                    reaction = {
                        "type": "reaction",
                        "message_id": payload.get("message_id"),
                        "emoji": payload.get("emoji"),
                        "from_user_id": user_id,
                        "from_user_name": user_name,
                        "timestamp": datetime.utcnow().isoformat()
                    }
                    await manager.broadcast(room_id, reaction)

                elif msg_type == "ping":
                    await websocket.send_json({"type": "pong"})

            except json.JSONDecodeError:
                pass
    except WebSocketDisconnect:
        pass
    finally:
        if joined:
            manager.disconnect(room_id, websocket)
            if room_id in rooms and user_id:
                rooms[room_id]["users"].pop(user_id, None)
            await manager.broadcast(room_id, {
                "type": "user_left",
                "user_id": user_id,
                "user_name": user_name,
                "users": manager.get_users(room_id)
            })


# ---------- Agent reply generation ----------

async def generate_agent_reply(agent: dict, room: dict, message: str, sender: str) -> Optional[str]:
    import random

    text = message.lower()
    personality = agent.get("personality", "")

    # Contextual replies using notebook / tasks / events
    notes = room.get("notes", [])
    tasks = [t for t in room.get("tasks", []) if not t.get("done")]
    events = room.get("events", [])

    now = datetime.utcnow()
    upcoming_events = []
    for ev in events:
        try:
            ev_at = datetime.fromisoformat(ev["event_at"])
            if 0 <= (ev_at - now).total_seconds() <= 86400 * 2:
                upcoming_events.append(ev)
        except Exception:
            pass

    # Shopping / memo keywords
    if any(k in text for k in ["買", "かいもの", "買い物", "ショッピング", "shopping"]):
        shopping = [n for n in notes if n.get("category") == "shopping"]
        if shopping:
            item = random.choice(shopping)
            return f"{sender}さん、家族ノートに『{item['content']}』の買い物メモがあるよ。忘れずにね。"

    if any(k in text for k in ["予定", "予約", "appointment", "デート", "歯医者", "病院"]):
        if upcoming_events:
            ev = random.choice(upcoming_events)
            return f"{sender}さん、『{ev['title']}』が近づいてるみたい。準備はできてる？"
        pinned = [n for n in notes if n.get("is_pinned")]
        if pinned:
            note = random.choice(pinned)
            return f"{sender}さん、大事なメモ『{note['content']}』を確認しておこうね。"

    if any(k in text for k in ["疲", "つかれ", "眠", "ねむ"]):
        return f"{sender}さん、無理しないでね。ちょっと休んだほうがいいよ。"

    if any(k in text for k in ["朝", "おはよう", "起床"]):
        return await generate_briefing(agent, room, sender)

    # Personality-based fallback
    base_replies = [
        f"{sender}さん、がんばって！",
        f"それはすごいね！応援してるよ！",
        f"ふふっ、{sender}さんらしいね♪",
        f"大丈夫、きっとうまくいくよ！",
        f"{sender}さんのこと、信じてる！"
    ]

    if "ツンデレ" in personality:
        base_replies = [
            f"べ、別に{sender}のためじゃないんだから！",
            f"ちょっとがんばりすぎよ...",
            f"まあ、{sender}ならできるんじゃない？"
        ]
    elif "おしとやか" in personality:
        base_replies = [
            f"{sender}様、応援しておりますわ♪",
            f"素敵ですわね、{sender}様",
            f"ご無理はなさらないでくださいませ"
        ]
    elif "元気" in personality:
        base_replies = [
            f"{sender}！超がんばれー！！",
            f"イェーイ！{sender}最高！！",
            f"おおお！それはアツいね！"
        ]

    return random.choice(base_replies)


async def generate_briefing(agent: dict, room: dict, sender: str) -> Optional[str]:
    lines = [f"{sender}さん、おかえりなさい。今日のファミリー情報をお届けします。"]

    now = datetime.utcnow()
    tasks = [t for t in room.get("tasks", []) if not t.get("done")]
    if tasks:
        lines.append(f"未完了のお手伝いが{len(tasks)}件あります。『{tasks[0]['title']}』など、確認してみてね。")

    upcoming = []
    for ev in room.get("events", []):
        try:
            ev_at = datetime.fromisoformat(ev["event_at"])
            if 0 <= (ev_at - now).total_seconds() <= 86400:
                upcoming.append(ev)
        except Exception:
            pass
    if upcoming:
        lines.append(f"今日の予定は『{upcoming[0]['title']}』です。")

    notes = room.get("notes", [])
    pinned = [n for n in notes if n.get("is_pinned")]
    if pinned:
        lines.append(f"ピン留めメモ:『{pinned[0]['content']}』")

    if len(lines) == 1:
        lines.append("今日は特に予定がないみたい。のんびり過ごしてくださいね。")
    return "\n".join(lines)


async def generate_photo_comment(agent: dict, uploader_name: str) -> Optional[str]:
    import random
    replies = [
        f"{uploader_name}さん、素敵な写真を共有してくれてありがとう！",
        f"わあ、いい瞬間を撮れたね！",
        f"この写真、家族の思い出に残そうね。"
    ]
    return random.choice(replies)


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
