import json
import os
import socket
import threading
import uuid
import asyncio
from datetime import datetime
from typing import Dict, List, Optional

from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Form, Request, Query
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware

app = FastAPI(title="FamiChibi Chat Server")

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
                        "version": "1.1.0",
                        "port": 8000,
                        "features": ["rooms", "password", "avatars", "websocket"]
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
        self.active_connections: Dict[str, List[dict]] = {}  # room_id -> [{ws, user_id, user_name}]

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
        if room_id in self.active_connections:
            for conn in self.active_connections[room_id]:
                if conn["user_id"] == user_id:
                    try:
                        await conn["ws"].send_json(message)
                    except Exception:
                        pass
                    break

manager = ConnectionManager()

# Ensure directories exist
os.makedirs("static", exist_ok=True)
os.makedirs("templates", exist_ok=True)

app.mount("/static", StaticFiles(directory="static"), name="static")

# Load chat.html content at startup
_chat_html = ""
try:
    with open("templates/chat.html", "r", encoding="utf-8") as f:
        _chat_html = f.read()
except Exception:
    pass


@app.get("/discover")
async def discover():
    return {
        "name": "FamiChibi Server",
        "version": "1.1.0",
        "port": 8000,
        "features": ["rooms", "password", "avatars", "websocket"]
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
        "agents": [],
        "users": {}  # user_id -> {user_name, joined_at}
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


@app.post("/rooms/{room_id}/messages")
async def send_message(room_id: str, sender: str = Form(...), content: str = Form(...)):
    if room_id not in rooms:
        return JSONResponse({"error": "Room not found"}, status_code=404)
    
    message = {
        "id": str(uuid.uuid4()),
        "sender": sender,
        "content": content,
        "type": "user",
        "timestamp": datetime.utcnow().isoformat()
    }
    rooms[room_id]["messages"].append(message)
    
    await manager.broadcast(room_id, message)
    
    # Check for AI agents in room
    for agent in rooms[room_id].get("agents", []):
        agent_reply = await generate_agent_reply(agent, content, sender)
        if agent_reply:
            reply_msg = {
                "id": str(uuid.uuid4()),
                "sender": agent["name"],
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


@app.post("/rooms/{room_id}/agents")
async def add_agent(room_id: str, name: str = Form(...), personality: str = Form("")):
    if room_id not in rooms:
        return JSONResponse({"error": "Room not found"}, status_code=404)
    
    agent = {
        "id": str(uuid.uuid4()),
        "name": name,
        "personality": personality
    }
    rooms[room_id]["agents"].append(agent)
    return agent


@app.get("/rooms/{room_id}/agents")
async def get_agents(room_id: str):
    if room_id not in rooms:
        return []
    return rooms[room_id].get("agents", [])


@app.websocket("/ws/{room_id}")
async def websocket_endpoint(websocket: WebSocket, room_id: str):
    user_id = None
    user_name = None
    joined = False
    
    try:
        await websocket.accept()
        
        # Wait for join message with auth
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
                
                # Send room info
                await websocket.send_json({
                    "type": "joined",
                    "room_id": room_id,
                    "room_name": room["name"],
                    "user_id": user_id,
                    "users": manager.get_users(room_id)
                })
                
                # Broadcast user joined
                await manager.broadcast(room_id, {
                    "type": "user_joined",
                    "user_id": user_id,
                    "user_name": user_name,
                    "users": manager.get_users(room_id)
                }, exclude_ws=websocket)
            else:
                await websocket.send_json({"type": "error", "message": "Expected join message"})
                await websocket.close()
                return
        except json.JSONDecodeError:
            await websocket.send_json({"type": "error", "message": "Invalid JSON"})
            await websocket.close()
            return
        
        # Main loop
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
                        await manager.broadcast(room_id, message)
                        
                        # Trigger agent replies
                        for agent in rooms[room_id].get("agents", []):
                            agent_reply = await generate_agent_reply(agent, message["content"], message["sender"])
                            if agent_reply:
                                reply_msg = {
                                    "id": str(uuid.uuid4()),
                                    "sender": agent["name"],
                                    "content": agent_reply,
                                    "type": "agent",
                                    "timestamp": datetime.utcnow().isoformat()
                                }
                                rooms[room_id]["messages"].append(reply_msg)
                                await manager.broadcast(room_id, reply_msg)
                
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


async def generate_agent_reply(agent: dict, message: str, sender: str) -> Optional[str]:
    """Generate a simple reply from an AI agent."""
    import random
    
    replies = [
        f"{sender}さん、がんばって！",
        f"それはすごいね！応援してるよ！",
        f"ふふっ、{sender}さんらしいね♪",
        f"大丈夫、きっとうまくいくよ！",
        f"{sender}さんのこと、信じてる！"
    ]
    
    personality = agent.get("personality", "")
    if "ツンデレ" in personality:
        replies = [
            f"べ、別に{sender}のためじゃないんだから！",
            f"ちょっとがんばりすぎよ...",
            f"まあ、{sender}ならできるんじゃない？"
        ]
    elif "おしとやか" in personality:
        replies = [
            f"{sender}様、応援しておりますわ♪",
            f"素敵ですわね、{sender}様",
            f"ご無理はなさらないでくださいませ"
        ]
    elif "元気" in personality:
        replies = [
            f"{sender}！超がんばれー！！",
            f"イェーイ！{sender}最高！！",
            f"おおお！それはアツいね！"
        ]
    
    return random.choice(replies)


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
