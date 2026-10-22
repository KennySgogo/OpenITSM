import json
import os
import unittest
from unittest.mock import patch
import httpx
from app.main import app


class AssigneeRecommendationTest(unittest.IsolatedAsyncioTestCase):
    async def call(self, output):
        candidate = "00000000-0000-0000-0000-000000000001"
        body = {"modelConnection": {"baseUrl": "http://127.0.0.1:9999/v1", "model": "test", "apiKey": "test-secret"},
                "ticket": {"title": "Network", "description": "Public issue", "impactScope": "Team", "businessType": "Network"},
                "candidates": [{"id": candidate, "skillsDescription": "Network skills", "openCount": 2, "resolvedCount": 5}]}
        real = httpx.AsyncClient
        seen = []
        def provider(request):
            seen.append(json.loads(request.content))
            return httpx.Response(200, json={"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(output)}}]})
        with patch.dict(os.environ, {"OPENITSM_AI_ALLOW_LOCAL_MODEL": "true"}):
            async with real(transport=httpx.ASGITransport(app=app, client=("127.0.0.1", 1)), base_url="http://ai") as client:
                with patch("app.main.httpx.AsyncClient", side_effect=lambda **kwargs: real(transport=httpx.MockTransport(provider), **kwargs)):
                    result = await client.post("/internal/v1/assignee-recommendations", json=body)
        return result, seen

    async def test_valid_and_empty_recommendations_use_only_supplied_material(self):
        for items in [[], [{"assigneeId": "00000000-0000-0000-0000-000000000001", "reason": "相关经验和技能"}]]:
            result, seen = await self.call({"items": items})
            self.assertEqual(result.status_code, 200)
            self.assertEqual(result.json(), {"items": items})
            self.assertEqual(len(seen), 1)
            materials = json.loads(seen[0]["messages"][1]["content"])
            self.assertEqual(materials["candidates"][0]["resolvedCount"], 5)
            self.assertNotIn("test-secret", seen[0]["messages"][1]["content"])

    async def test_foreign_duplicate_excessive_and_invalid_reasons_are_rejected(self):
        item = {"assigneeId": "00000000-0000-0000-0000-000000000001", "reason": "skill"}
        for output in [{"items": [dict(item, assigneeId="foreign")]}, {"items": [item, item]},
                       {"items": [item] * 4}, {"items": [dict(item, reason=" ")]},
                       {"items": [dict(item, reason="x" * 201)]}, {"items": [], "extra": 1}]:
            result, _ = await self.call(output)
            self.assertEqual(result.status_code, 503)
            self.assertEqual(result.json()["code"], "AI_INVALID_RESPONSE")
