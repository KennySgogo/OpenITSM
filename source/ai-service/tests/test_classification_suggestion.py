import asyncio
import json
import os
import unittest
from unittest.mock import patch

import httpx

from app.main import app


TOKEN = "m09-private-http-test-token-1234567890"
SECRET = "m09-provider-test-secret"
TYPE_ID = "00000000-0000-0000-0000-000000000091"


class ClassificationSuggestionTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        environment = patch.dict(os.environ, {"OPENITSM_AI_INTERNAL_TOKEN": TOKEN, "OPENITSM_AI_ALLOW_LOCAL_MODEL": "true"})
        environment.start()
        self.addCleanup(environment.stop)

    def body(self):
        return {"modelConnection": {"baseUrl": "http://127.0.0.1:9999/v1", "model": "test", "apiKey": SECRET},
                "ticket": {"title": "网络故障", "description": "无法连接公司网络", "impactScope": "团队"},
                "businessTypes": [{"id": TYPE_ID, "name": "网络"}]}

    async def call(self, body, provider=None, token=TOKEN):
        real_client = httpx.AsyncClient
        async with real_client(transport=httpx.ASGITransport(app=app), base_url="http://ai") as client:
            if provider is None:
                return await client.post("/internal/v1/classification-suggestions",
                                         headers={"Authorization": "Bearer " + token}, json=body)
            with patch("app.main.httpx.AsyncClient", side_effect=lambda **kwargs: real_client(
                    transport=httpx.MockTransport(provider), **kwargs)):
                return await client.post("/internal/v1/classification-suggestions",
                                         headers={"Authorization": "Bearer " + token}, json=body)

    async def test_only_supplied_public_materials_reach_model_and_result_is_suggestion(self):
        seen = []

        def provider(request):
            seen.append(request)
            return httpx.Response(200, json={"choices": [{"message": {"content": json.dumps({
                "businessTypeId": TYPE_ID, "reason": "与网络连接有关", "suggestedPriority": "P2"})}}]})

        response = await self.call(self.body(), provider)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"businessTypeId": TYPE_ID, "reason": "与网络连接有关", "suggestedPriority": "P2"})
        self.assertEqual(response.headers["Cache-Control"], "no-store")
        self.assertEqual(len(seen), 1)
        self.assertEqual(seen[0].headers["Authorization"], "Bearer " + SECRET)
        messages = json.loads(seen[0].content)["messages"]
        materials = json.loads(messages[1]["content"])
        self.assertEqual(materials, {"ticket": self.body()["ticket"], "businessTypes": self.body()["businessTypes"]})
        self.assertNotIn(SECRET, messages[0]["content"] + messages[1]["content"])

    async def test_rejects_unauthorized_or_unexpected_materials_before_provider(self):
        self.assertEqual((await self.call(self.body(), token="wrong")).status_code, 401)
        for changed in [dict(self.body(), internalNote="private"), dict(self.body(), businessTypes=[]),
                        dict(self.body(), ticket={"title": "x", "description": "x", "impactScope": "x", "attachment": "private"})]:
            response = await self.call(changed)
            self.assertEqual(response.status_code, 400)
            self.assertNotIn(SECRET, response.text)
        oversized = dict(self.body(), ticket={"title": "x", "description": "x" * 140000, "impactScope": "x"})
        self.assertEqual((await self.call(oversized)).status_code, 413)

    async def test_type_descriptions_reach_provider_and_are_bounded(self):
        body = self.body()
        body["businessTypes"][0]["description"] = "Network connections, excluding account permissions"
        seen = []

        def provider(request):
            seen.append(json.loads(json.loads(request.content)["messages"][1]["content"]))
            return httpx.Response(200, json={"choices": [{"message": {"content": json.dumps({
                "businessTypeId": TYPE_ID, "reason": "Network issue", "suggestedPriority": "P3"})}}]})

        self.assertEqual((await self.call(body, provider)).status_code, 200)
        self.assertEqual(seen[0]["businessTypes"], body["businessTypes"])
        body["businessTypes"][0]["description"] = "x" * 501
        self.assertEqual((await self.call(body)).status_code, 400)

    async def test_rejects_invalid_model_candidates_priority_shape_and_provider_failure(self):
        invalid = [{"businessTypeId": "00000000-0000-0000-0000-000000000099", "reason": "x", "suggestedPriority": "P2"},
                   {"businessTypeId": TYPE_ID, "reason": "x", "suggestedPriority": "P0"},
                   {"businessTypeId": TYPE_ID, "reason": "x", "suggestedPriority": None, "tag": "invented"},
                   {"businessTypeId": TYPE_ID, "reason": " ", "suggestedPriority": None},
                   {"businessTypeId": None, "reason": "unsupported", "suggestedPriority": None}]
        for content in invalid:
            with self.subTest(content=content):
                response = await self.call(self.body(), lambda request: httpx.Response(200, json={
                    "choices": [{"message": {"content": json.dumps(content)}}]}))
                self.assertEqual(response.status_code, 503)
                self.assertEqual(response.json(), {"code": "AI_INVALID_RESPONSE"})
        for provider_response in [httpx.Response(302, headers={"Location": "https://other.example"}),
                                  httpx.Response(503, text=SECRET)]:
            response = await self.call(self.body(), lambda request: provider_response)
            self.assertEqual(response.json(), {"code": "DEPENDENCY_UNAVAILABLE"})
            self.assertNotIn(SECRET, response.text)

    async def test_allows_no_type_suggestion_and_bounds_total_wait(self):
        empty = {"businessTypeId": None, "reason": None, "suggestedPriority": "P3"}
        response = await self.call(self.body(), lambda request: httpx.Response(200, json={
            "choices": [{"message": {"content": json.dumps(empty)}}]}))
        self.assertEqual(response.json(), empty)

        async def slow(request):
            await asyncio.sleep(1)
            return httpx.Response(200)

        with patch("app.main.TOTAL_TIMEOUT_SECONDS", 0.01):
            response = await self.call(self.body(), slow)
        self.assertEqual(response.status_code, 504)
        self.assertEqual(response.json(), {"code": "AI_TIMEOUT"})


if __name__ == "__main__":
    unittest.main()
