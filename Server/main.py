import json
import os
import uuid
from datetime import datetime
from typing import Dict, List, Optional

from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Form, Request
from fastapi.responses import HTMLResponse
from fastapi.staticfiles import StaticFiles

app = FastAPI(title="FamiChibi Chat Server")

# In-memory storage
rooms: Dict[str, Dict] = {}

class ConnectionManager:
    def __init__(self):
        self.active_connections: Dict[str, List[WebSocket]] = {}

    async def connect(self, room_id: str, websocket: WebSocket):
        await websocket.accept()
        if room_id not in self.active_connections:
            self.active_connections[room_id] = []
        self.active_connections[room_id].append(websocket)

    def disconnect(self, room_id: str, websocket: WebSocket):
        if room_id in self.active_connections:
            if websocket in self.active_connections[room_id]:
                self.active_connections[room_id].remove(websocket)

    async def broadcast(self, room_id: str, message: dict):
        if room_id in self.active_connections:
            disconnected = []
            for connection in self.active_connections[room_id]:
                try:
                    await connection.send_json(message)
                except Exception:
                    disconnected.append(connection)
            for d in disconnected:
                self.disconnect(room_id, d)

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


@app.get("/", response_class=HTMLResponse)
async def chat_ui():
    return _chat_html if _chat_html else "<h1>FamiChibi Chat Server</h1><p>Server is running.</p>"


@app.post("/rooms/{room_id}/messages")
async def send_message(room_id: str, sender: str = Form(...), content: str = Form(...)):
    if room_id not in rooms:
        rooms[room_id] = {"messages": [], "agents": []}
    
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
        rooms[room_id] = {"messages": [], "agents": []}
    
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
    await manager.connect(room_id, websocket)
    try:
        while True:
            data = await websocket.receive_text()
            try:
                payload = json.loads(data)
                msg_type = payload.get("type", "message")
                
                if msg_type == "message":
                    message = {
                        "id": str(uuid.uuid4()),
                        "sender": payload.get("sender", "匿名"),
                        "content": payload.get("content", ""),
                        "type": "user",
                        "timestamp": datetime.utcnow().isoformat()
                    }
                    if room_id not in rooms:
                        rooms[room_id] = {"messages": [], "agents": []}
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
        manager.disconnect(room_id, websocket)


async def generate_agent_reply(agent: dict, message: str, sender: str) -> Optional[str]:
    """Generate a simple reply from an AI agent."""
    import random
    
    # Simple rule-based responses
    replies = [
        f"{sender}さん、がんばって！",
        f"それはすごいね！応援してるよ！",
        f"ふふっ、{sender}さんらしいね♪",
        f"大丈夫、きっとうまくいくよ！",
        f"{sender}さんのこと、信じてる！"
    ]
    
    # Personality-based variation
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
