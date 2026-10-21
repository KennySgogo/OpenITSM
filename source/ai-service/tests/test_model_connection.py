import asyncio
import json
import os
import unittest
from unittest.mock import patch

import httpx
from app.main import app, FIXED_TEST_TEXT, InternalError, ModelConnection, model_test

TOKEN = "m14-test-internal-token-only-12345678"
SECRET = "m14-test-model-secret"


class ConnectionTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.environment = patch.dict(os.environ, {"OPENITSM_AI_INTERNAL_TOKEN": TOKEN, "OPENITSM_AI_ALLOW_LOCAL_MODEL": "true"})
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.connection = ModelConnection(baseUrl="http://127.0.0.1:9999/v1", model="test", apiKey=SECRET)

    async def call(self, body, token=TOKEN):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://ai") as client:
            return await client.post("/internal/v1/model-connection-tests", headers={"Authorization": "Bearer " + token}, json=body)

    def body(self):
        return {"modelConnection": {"baseUrl": "http://127.0.0.1:9999/v1", "model": "test", "apiKey": SECRET}}

    async def test_internal_authentication_fails_closed_without_leaking_input(self):
        response = await self.call(self.body(), "wrong")
        self.assertEqual(response.status_code, 401)
        self.assertNotIn(SECRET, response.text)
        with patch.dict(os.environ, {"OPENITSM_AI_INTERNAL_TOKEN": ""}):
            self.assertEqual((await self.call(self.body())).status_code, 503)

    async def test_rejects_custom_prompts_and_sanitizes_validation_errors(self):
        body = self.body()
        body["prompt"] = "sensitive-ticket-text"
        response = await self.call(body)
        self.assertEqual(response.status_code, 400)
        self.assertNotIn(SECRET, response.text)
        self.assertNotIn("sensitive-ticket-text", response.text)
        body = self.body()
        body["modelConnection"]["apiKey"] = None
        self.assertEqual((await self.call(body)).status_code, 400)

    async def test_fixed_text_authentication_and_no_provider_content_in_response(self):
        captured = []

        def provider(request):
            captured.append(request)
            return httpx.Response(200, json={"choices": [{"message": {"content": "provider-private-text"}}]})

        real_client = httpx.AsyncClient
        with patch("app.main.httpx.AsyncClient", side_effect=lambda **kwargs: real_client(transport=httpx.MockTransport(provider), **kwargs)):
            await model_test(self.connection)
        self.assertEqual(len(captured), 1)
        self.assertEqual(captured[0].headers["Authorization"], "Bearer " + SECRET)
        self.assertEqual(json.loads(captured[0].content)["messages"], [{"role": "user", "content": FIXED_TEST_TEXT}])

    async def test_provider_failures_invalid_outputs_and_no_redirects(self):
        cases = [(httpx.Response(401, text=SECRET), "DEPENDENCY_UNAVAILABLE"),
                 (httpx.Response(302, headers={"Location": "https://other.example"}), "DEPENDENCY_UNAVAILABLE"),
                 (httpx.Response(200, text="not-json"), "AI_INVALID_RESPONSE"),
                 (httpx.Response(200, json={"choices": []}), "AI_INVALID_RESPONSE"),
                 (httpx.Response(200, content=b"x" * 65537), "AI_INVALID_RESPONSE")]
        real_client = httpx.AsyncClient
        for response, code in cases:
            with self.subTest(code=code), patch("app.main.httpx.AsyncClient", side_effect=lambda **kwargs: real_client(transport=httpx.MockTransport(lambda request: response), **kwargs)):
                with self.assertRaises(InternalError) as error:
                    await model_test(self.connection)
                self.assertEqual(error.exception.code, code)

    async def test_total_deadline_and_network_timeouts(self):
        async def slow(request):
            await asyncio.sleep(1)
            return httpx.Response(200)
        real_client = httpx.AsyncClient
        with patch("app.main.TOTAL_TIMEOUT_SECONDS", 0.01), patch("app.main.httpx.AsyncClient", side_effect=lambda **kwargs: real_client(transport=httpx.MockTransport(slow), **kwargs)):
            with self.assertRaises(InternalError) as error:
                await model_test(self.connection)
            self.assertEqual(error.exception.code, "AI_TIMEOUT")

    async def test_route_returns_only_status_and_enforces_body_limit(self):
        with patch("app.main.model_test", return_value=None):
            response = await self.call(self.body())
        self.assertEqual(response.json(), {"status": "SUCCESS"})
        self.assertEqual(response.headers["Cache-Control"], "no-store")
        response = await self.call({"body": "x" * 17000})
        self.assertEqual(response.status_code, 413)

    def test_external_cleartext_and_credential_urls_are_rejected(self):
        for url in ["http://example.com/v1", "https://user:password@example.com/v1", "https://example.com/v1?token=secret", "file:///etc/passwd"]:
            with self.subTest(url=url), self.assertRaises(ValueError):
                ModelConnection(baseUrl=url, model="test", apiKey=SECRET)
        self.assertNotIn(SECRET, repr(self.connection))


if __name__ == "__main__":
    unittest.main()
