import json
import os
import unittest
from unittest.mock import patch
import httpx
from app.main import app


class ReplyDraftTest(unittest.IsolatedAsyncioTestCase):
    async def call(self, output, sources):
        body = {"modelConnection": {"baseUrl": "http://127.0.0.1:9999/v1", "model": "test", "apiKey": "test-secret"},
                "ticket": {"title": "VPN failure", "description": "VPN connection failed", "businessType": "Network"}, "sources": sources}
        real = httpx.AsyncClient
        seen = []
        def provider(request):
            seen.append(json.loads(request.content))
            return httpx.Response(200, json={"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(output)}}]})
        with patch.dict(os.environ, {"OPENITSM_AI_ALLOW_LOCAL_MODEL": "true"}):
            async with real(transport=httpx.ASGITransport(app=app, client=("127.0.0.1", 1)), base_url="http://ai") as client:
                with patch("app.main.httpx.AsyncClient", side_effect=lambda **kwargs: real(transport=httpx.MockTransport(provider), **kwargs)):
                    result = await client.post("/internal/v1/reply-drafts", json=body)
        return result, seen

    async def test_draft_with_and_without_sources(self):
        sources = [{"sourceKey": "KNOWLEDGE:00000000-0000-0000-0000-000000000001", "title": "VPN", "text": "检查证书"}]
        for supplied in ([], sources):
            result, seen = await self.call({"content": "请检查网络连接。"}, supplied)
            self.assertEqual(result.status_code, 200)
            self.assertEqual(result.json(), {"content": "请检查网络连接。"})
            self.assertEqual(json.loads(seen[0]["messages"][1]["content"])["sources"], supplied)
            self.assertNotIn("test-secret", seen[0]["messages"][1]["content"])

    async def test_invalid_outputs_are_rejected(self):
        for output in ({"content": " "}, {"content": "x" * 2001}, {"content": "ok", "sourceId": "invented"}, {"content": "a\u0000b"}, {}):
            result, _ = await self.call(output, [])
            self.assertEqual(result.status_code, 503)
            self.assertEqual(result.json()["code"], "AI_INVALID_RESPONSE")
