import json
import os
import unittest
from unittest.mock import patch
import httpx
from app.main import app


KEY = "KNOWLEDGE:00000000-0000-0000-0000-000000000001"


class SimilarIssueTest(unittest.IsolatedAsyncioTestCase):
    async def call(self, output):
        body = {"modelConnection": {"baseUrl": "http://127.0.0.1:9999/v1", "model": "test", "apiKey": "test-secret"},
                "ticket": {"title": "VPN failure", "description": "VPN connection failed", "businessType": "Network"},
                "sources": [{"sourceKey": KEY, "title": "VPN solution", "text": "Update client certificate"}]}
        real = httpx.AsyncClient
        seen = []
        def provider(request):
            seen.append(json.loads(request.content))
            return httpx.Response(200, json={"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(output)}}]})
        with patch.dict(os.environ, {"OPENITSM_AI_ALLOW_LOCAL_MODEL": "true"}):
            async with real(transport=httpx.ASGITransport(app=app, client=("127.0.0.1", 1)), base_url="http://ai") as client:
                with patch("app.main.httpx.AsyncClient", side_effect=lambda **kwargs: real(transport=httpx.MockTransport(provider), **kwargs)):
                    result = await client.post("/internal/v1/similar-issues", json=body)
        return result, seen

    async def test_real_source_selection_and_empty_result(self):
        for items in [[], [{"sourceKey": KEY, "reason": "相同证书问题"}]]:
            result, seen = await self.call({"items": items})
            self.assertEqual(result.status_code, 200)
            self.assertEqual(result.json(), {"items": items})
            self.assertEqual(len(seen), 1)
            materials = json.loads(seen[0]["messages"][1]["content"])
            self.assertEqual(materials["sources"][0]["text"], "Update client certificate")
            self.assertNotIn("test-secret", seen[0]["messages"][1]["content"])

    async def test_hallucinated_duplicate_extra_or_excessive_sources_fail(self):
        item = {"sourceKey": KEY, "reason": "related"}
        for output in [{"items": [dict(item, sourceKey="invented")]}, {"items": [item, item]},
                       {"items": [item] * 6}, {"items": [dict(item, excerpt="invented")]},
                       {"items": [dict(item, reason=" ")]}, {"items": [dict(item, reason="x" * 201)]},
                       {"items": [], "url": "https://invented.example"}]:
            result, _ = await self.call(output)
            self.assertEqual(result.status_code, 503)
            self.assertEqual(result.json()["code"], "AI_INVALID_RESPONSE")
