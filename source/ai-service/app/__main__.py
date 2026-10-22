"""Same-host AI service entry point; proxy headers never supply client identity."""
import uvicorn

if __name__ == "__main__":
    uvicorn.run("app.main:app", host="127.0.0.1", port=8001, proxy_headers=False, access_log=False)
