#!/usr/bin/env python3
"""
Gets an access token for an IAM administrator: authorization code flow with PKCE, redirect to 127.0.0.1.
Needed once at the start, because the admin API wants a bearer token and no console exists yet.

  python3 tools/iam_token.py --issuer https://iam.example.com           # opens the browser; prints the access token
  python3 tools/iam_token.py --issuer https://iam.example.com --refresh <refresh token>

The token goes to stdout, everything else to stderr, so it can be captured:
  TOKEN=$(python3 tools/iam_token.py --issuer https://iam.example.com)
  curl -H "Authorization: Bearer $TOKEN" https://iam.example.com/api/v1/users

Standard library only. The client "iam-admin-cli" is created on the first start together with the bootstrap administrator.
"""
import argparse, base64, hashlib, http.server, json, os, secrets, sys, threading, urllib.error, urllib.parse, urllib.request, webbrowser


def b64url(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def post_form(url, form):
    req = urllib.request.Request(url, data=urllib.parse.urlencode(form).encode(), method="POST",
                                 headers={"Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        sys.exit("Token request refused (%s): %s" % (e.code, e.read().decode(errors="replace")[:300]))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--issuer", default=os.environ.get("IAM_ISSUER"), help="public base URL of the IAM service (or set IAM_ISSUER)")
    ap.add_argument("--client", default="iam-admin-cli")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--no-browser", action="store_true", help="print the address instead of opening it")
    ap.add_argument("--refresh", help="exchange a refresh token instead of signing in")
    ap.add_argument("--show-all", action="store_true", help="print the whole token response as JSON")
    a = ap.parse_args()
    if not a.issuer:
        sys.exit("--issuer (or IAM_ISSUER) is required")
    issuer = a.issuer.rstrip("/")

    if a.refresh:
        tok = post_form(issuer + "/oauth2/token", {"grant_type": "refresh_token", "refresh_token": a.refresh, "client_id": a.client})
        return emit(tok, a.show_all)

    redirect = "http://127.0.0.1:%d/callback" % a.port
    verifier = b64url(secrets.token_bytes(48))
    challenge = b64url(hashlib.sha256(verifier.encode()).digest())
    state = b64url(secrets.token_bytes(16))
    got = {}
    done = threading.Event()

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            if urllib.parse.urlparse(self.path).path != "/callback":
                self.send_response(404); self.end_headers(); return
            got.update({k: v[0] for k, v in q.items()})
            self.send_response(200); self.send_header("Content-Type", "text/plain; charset=utf-8"); self.end_headers()
            self.wfile.write(b"Signed in. You can close this tab and return to the terminal.")
            done.set()

        def log_message(self, *args):
            pass

    server = http.server.HTTPServer(("127.0.0.1", a.port), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    url = issuer + "/oauth2/authorize?" + urllib.parse.urlencode({
        "response_type": "code", "client_id": a.client, "redirect_uri": redirect, "scope": "openid profile",
        "code_challenge": challenge, "code_challenge_method": "S256", "state": state})
    print("Open this address and sign in (password, then the code from your authenticator app):\n  " + url, file=sys.stderr)
    if not a.no_browser:
        webbrowser.open(url)
    if not done.wait(300):
        sys.exit("Timed out waiting for the sign-in.")
    server.shutdown()
    if "error" in got:
        sys.exit("Sign-in refused: %s %s" % (got.get("error"), got.get("error_description", "")))
    if got.get("state") != state:
        sys.exit("The state did not match; start again.")
    tok = post_form(issuer + "/oauth2/token", {"grant_type": "authorization_code", "code": got["code"], "redirect_uri": redirect,
                                               "client_id": a.client, "code_verifier": verifier})
    emit(tok, a.show_all)


def emit(tok, show_all):
    if show_all:
        print(json.dumps(tok, indent=2))
        return
    if tok.get("refresh_token"):
        print("Refresh token (keep it private; it lasts hours):\n  " + tok["refresh_token"], file=sys.stderr)
    print("Access token valid for %s seconds." % tok.get("expires_in"), file=sys.stderr)
    print(tok["access_token"])


if __name__ == "__main__":
    main()
