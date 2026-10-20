"""Private computation API. No database, business writes, or browser CORS."""
import asyncio
import hmac
import json
import os
from urllib.parse import urlsplit

import httpx
from fastapi import Depends, FastAPI, Header, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, SecretStr, field_validator

app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
FIXED_TEST_TEXT = "Reply with OK. This is an OpenITSM connection test."
TOTAL_TIMEOUT_SECONDS = 10
MAX_RESPONSE_BYTES = 65536


class ModelConnection(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    baseUrl: str = Field(min_length=1, max_length=2048)
    model: str = Field(min_length=1, max_length=200)
    apiKey: SecretStr

    @field_validator("apiKey")
    @classmethod
    def validate_key(cls, value: SecretStr) -> SecretStr:
        text = value.get_secret_value()
        if not text.strip() or len(text) > 512 or any(ord(c) < 32 or ord(c) == 127 for c in text):
            raise ValueError("Invalid credential")
        return value

    @field_validator("baseUrl")
    @classmethod
    def validate_url(cls, value: str) -> str:
        url = urlsplit(value)
        local = os.environ.get("OPENITSM_AI_ALLOW_LOCAL_MODEL") == "true" and url.hostname in {"127.0.0.1", "localhost", "::1"}
        if (not url.hostname or (url.port is not None and not 1 <= url.port <= 65535) or url.username or url.password or url.query or url.fragment
                or not (url.scheme == "https" or (local and url.scheme == "http"))
                or any(ord(c) <= 32 for c in value)):
            raise ValueError("Invalid model URL")
        return value.rstrip("/")


class ConnectionTestRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    modelConnection: ModelConnection


async def require_internal(authorization: str | None = Header(default=None)) -> None:
    token = os.environ.get("OPENITSM_AI_INTERNAL_TOKEN", "")
    if len(token) < 32:
        raise InternalError(503, "DEPENDENCY_UNAVAILABLE")
    if not authorization or not hmac.compare_digest(authorization.encode(), ("Bearer " + token).encode()):
        raise InternalError(401, "UNAUTHORIZED")


class InternalError(Exception):
    def __init__(self, status: int, code: str):
        self.status = status
        self.code = code


@app.exception_handler(InternalError)
async def internal_error(request: Request, exc: InternalError):
    return JSONResponse(status_code=exc.status, content={"code": exc.code})


@app.exception_handler(RequestValidationError)
async def invalid_request(request: Request, exc: RequestValidationError):
    # Pydantic errors contain input values, including credentials. Never return or log them.
    return JSONResponse(status_code=400, content={"code": "VALIDATION_FAILED"})


@app.exception_handler(Exception)
async def unexpected_error(request: Request, exc: Exception):
    return JSONResponse(status_code=503, content={"code": "DEPENDENCY_UNAVAILABLE"})


@app.middleware("http")
async def private_boundary(request: Request, call_next):
    # Check before body parsing to avoid unauthenticated large/secret-bearing payloads.
    try:
        await require_internal(request.headers.get("authorization"))
    except InternalError as exc:
        return JSONResponse(status_code=exc.status, content={"code": exc.code})
    size = 0
    chunks = []
    async for chunk in request.stream():
        size += len(chunk)
        if size > 16384:
            return JSONResponse(status_code=413, content={"code": "REQUEST_TOO_LARGE"})
        chunks.append(chunk)
    request._body = b"".join(chunks)
    response = await call_next(request)
    response.headers["Cache-Control"] = "no-store"
    return response


async def model_test(connection: ModelConnection) -> None:
    timeout = httpx.Timeout(connect=2, read=5, write=3, pool=2)
    # HTTPX's per-operation timeouts do not bound a slow stream; asyncio also bounds the entire request.
    try:
        async with asyncio.timeout(TOTAL_TIMEOUT_SECONDS):
            async with httpx.AsyncClient(timeout=timeout, follow_redirects=False, trust_env=False) as client:
                async with client.stream("POST", connection.baseUrl + "/chat/completions",
                        headers={"Authorization": "Bearer " + connection.apiKey.get_secret_value()},
                        json={"model": connection.model, "messages": [{"role": "user", "content": FIXED_TEST_TEXT}],
                              "stream": False, "max_tokens": 16}) as response:
                    if response.status_code != 200:
                        raise InternalError(503, "DEPENDENCY_UNAVAILABLE")
                    body = bytearray()
                    async for chunk in response.aiter_bytes():
                        body.extend(chunk)
                        if len(body) > MAX_RESPONSE_BYTES:
                            raise InternalError(503, "AI_INVALID_RESPONSE")
                    try:
                        result = json.loads(body)
                        content = result["choices"][0]["message"]["content"]
                        if not isinstance(content, str) or not content.strip():
                            raise ValueError()
                    except (ValueError, KeyError, TypeError, IndexError):
                        raise InternalError(503, "AI_INVALID_RESPONSE") from None
    except (TimeoutError, httpx.TimeoutException):
        raise InternalError(504, "AI_TIMEOUT") from None
    except httpx.HTTPError:
        raise InternalError(503, "DEPENDENCY_UNAVAILABLE") from None
    except InternalError:
        raise
    except Exception:
        # Even local transport/encoding exceptions must not reach the ASGI server's traceback log.
        raise InternalError(503, "DEPENDENCY_UNAVAILABLE") from None


@app.post("/internal/v1/model-connection-tests", dependencies=[Depends(require_internal)])
async def test_connection(body: ConnectionTestRequest):
    await model_test(body.modelConnection)
    return {"status": "SUCCESS"}
