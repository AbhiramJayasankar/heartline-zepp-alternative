"""FastAPI app: REST + WebSocket over a single Watch, plus the static dashboard."""
import asyncio
import pathlib
import time
from contextlib import asynccontextmanager
from typing import Any, Awaitable

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles

from . import protocol
from .insights import make_router
from .store import Store, activity_summary, hr_stats
from .watch import NotConnected, Watch, load_config

ROOT = pathlib.Path(__file__).resolve().parent.parent


class Hub:
    """Fans out watch events to every open WebSocket."""

    def __init__(self) -> None:
        self.queues: set[asyncio.Queue] = set()

    def publish(self, msg: dict) -> None:
        for q in list(self.queues):
            q.put_nowait(msg)


def create_app(watch: Any = None, store: Store | None = None, hub: Hub | None = None) -> FastAPI:
    hub = hub or Hub()
    store = store or Store(ROOT / "data" / "watch.db")
    watch = watch or Watch(load_config(), store, hub.publish)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        yield
        await watch.shutdown()

    app = FastAPI(title="GTR 2e dashboard", lifespan=lifespan)
    app.state.watch, app.state.store, app.state.hub = watch, store, hub

    async def call(work: Awaitable | Any) -> Any:
        try:
            result = await work if asyncio.iscoroutine(work) else work
        except NotConnected as e:
            return JSONResponse({"error": str(e)}, status_code=409)
        except Exception as e:
            return JSONResponse({"error": str(e) or type(e).__name__}, status_code=500)
        return result

    @app.get("/api/status")
    async def status():
        return watch.status()

    @app.post("/api/connect")
    async def connect():
        await watch.connect()
        return watch.status()

    @app.post("/api/disconnect")
    async def disconnect():
        return await call(_disconnect())

    async def _disconnect():
        await watch.disconnect()
        return watch.status()

    @app.get("/api/battery")
    async def battery():
        return await call(watch.read_battery())

    @app.get("/api/device-info")
    async def device_info():
        return await call(watch.device_info())

    @app.post("/api/hr/start")
    async def hr_start():
        return await call(_streaming(watch.hr_start()))

    @app.post("/api/hr/stop")
    async def hr_stop():
        return await call(_streaming(watch.hr_stop()))

    async def _streaming(work: Awaitable) -> dict:
        return {"streaming": await work}

    @app.get("/api/hr/history")
    async def hr_history(minutes: float = 60):
        samples = store.hr_since(time.time() - minutes * 60)
        return {"samples": samples, "stats": hr_stats(samples)}

    @app.get("/api/gatt")
    async def gatt():
        return await call(_gatt())

    async def _gatt():
        return {"services": watch.gatt()}

    @app.post("/api/raw/probe")
    async def raw_probe():
        return await call(watch.raw_probe())

    @app.post("/api/raw/start")
    async def raw_start():
        return await call(_streaming(watch.raw_start()))

    @app.post("/api/raw/stop")
    async def raw_stop():
        return await call(_streaming(watch.raw_stop()))

    @app.post("/api/activity/sync")
    async def activity_sync():
        return await call(watch.activity_sync())

    @app.get("/api/activity")
    async def activity(hours: float = 24):
        since = time.time() - hours * 3600
        rows = store.activity_since(since)
        states = protocol.activity_states(rows, store.activity_prev_kind(since))
        samples = [
            {"ts": r["ts"], "kind": r["kind"], "state": st, "intensity": r["intensity"], "steps": r["steps"],
             "hr": r["hr"] if protocol.valid_hr(r["hr"]) else None,
             "sleep": r["sleep"], "deep": r["deep"], "rem": r["rem"]}
            for r, st in zip(rows, states)
        ]
        last_sync = store.get_meta("activity_last_sync")
        return {"samples": samples, "summary": activity_summary(samples),
                "last_sync": float(last_sync) if last_sync else None}

    @app.post("/api/vibrate")
    async def vibrate():
        return await call(watch.vibrate())

    @app.get("/api/events")
    async def events(limit: int = 100):
        return {"events": store.events(limit)}

    @app.websocket("/ws")
    async def ws(socket: WebSocket):
        await socket.accept()
        queue: asyncio.Queue = asyncio.Queue()
        hub.queues.add(queue)

        async def sender():
            await socket.send_json({"type": "status", **watch.status()})
            while True:
                await socket.send_json(await queue.get())

        send_task = asyncio.create_task(sender())
        try:
            while True:
                await socket.receive_text()
        except WebSocketDisconnect:
            pass
        finally:
            send_task.cancel()
            hub.queues.discard(queue)

    # Insights and profile (server/insights.py); must come before the static mount at "/".
    app.include_router(make_router(store, watch, hub.publish, ROOT / "data" / "raw"))

    web = ROOT / "web"
    web.mkdir(exist_ok=True)
    app.mount("/", StaticFiles(directory=web, html=True), name="web")
    return app


def __getattr__(name: str):
    # Build the real app lazily so importing the module (e.g. in tests) never opens the DB or config.
    if name == "app":
        globals()["app"] = create_app()
        return globals()["app"]
    raise AttributeError(name)
