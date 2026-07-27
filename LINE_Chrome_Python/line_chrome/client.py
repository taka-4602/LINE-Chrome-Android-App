# -*- coding: utf-8 -*-
"""
line_chrome.client
==================
A pure-Python LINE client that mimics the LINE Chrome Extension (CHROMEOS device).

Quick start
-----------
    from line_chrome import LINE

    line = LINE("you@example.com", "yourpassword")
    line.send_message("Cxxxxxxx...", "Hello!")

    @line.on_message
    def handler(msg, op):
        print(msg.text, msg.sender)

    line.listen()            # blocks; Ctrl-C to stop
"""
import base64
import binascii
import hashlib
import hmac
import json
import os
import struct
import threading
import time
from hashlib import md5
from typing import Any, Callable, Dict, List, Optional, Union

import xxhash
import httpx
import requests as _requests
import rsa
from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey,
    X25519PublicKey,
)
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from Crypto.Cipher import AES, PKCS1_OAEP as _PKCS1_OAEP
from Crypto.PublicKey import RSA as _CryptoRSA
from Crypto.Util.Padding import pad, unpad

from .thrift import (
    LineServiceError,
    pack_binary,
    pack_binary_struct,
    pack_compact,
    unpack_binary,
    unpack_compact,
    unpack_compact_struct,
)
from .types import (
    Chat,
    ChatSummary,
    ChatType,
    Contact,
    ContentType,
    Message,
    Operation,
    OpType,
    Profile,
    SendMessageResult,
)

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

APP_TYPE    = "DESKTOPWIN"
APP_VER     = "8.6.0.3277"
SYSTEM_NAME = "WINDOWS"
SYSTEM_VER  = "10.0.0-NT-x64"
SYSTEM_MODEL = "WindowsPC"

_APP_NAME = f"{APP_TYPE}\t{APP_VER}\t{SYSTEM_NAME}\t{SYSTEM_VER}"
_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 11.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/115.0.0.0 Safari/537.36"
)

# encType=0: talk/poll go through gwz; auth must go through legy
_HOST_GW    = "https://gwz.line.naver.jp"
_HOST_LEGY  = "https://gf.line.naver.jp"   # legy encryption proxy
_EP_LEGY    = "/enc"

_EP_TALK    = "/S4"      # TalkService (TCompact)
# /P4 is the plain-TCompact poll endpoint, mirroring /S4 for talk.  /P5 is the
# TMoreCompact variant (like /S5) and needs a decoder this module does not have.
_EP_POLL    = "/P4"      # fetchOps    (TCompact, encType=0)
_EP_SYNC    = "/SYNC5"   # sync        (TMoreCompact, encType=0)
_EP_RS      = "/api/v3p/rs"
_EP_TALK_DO = "/api/v3/TalkService.do"
# Object storage (images/files) rides the same gateway under an /oa prefix.
_EP_OBS     = "/oa/r/talk/m/reqseq"

# Op-type for received message
OP_RECEIVE_MESSAGE = 26

# Cache dirs (.data / .tokens / .refreshToken) live beside the package, not in the
# CWD — otherwise running from a different directory silently loses the cached
# cert and forces the PIN dance again (or fails outright on a read-only CWD).
_DEFAULT_SAVE_PATH = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


# ---------------------------------------------------------------------------
# Crypto helpers (used during login only)
# ---------------------------------------------------------------------------

def _sha256(*args: bytes) -> bytes:
    h = hashlib.sha256()
    for a in args:
        h.update(a if isinstance(a, (bytes, bytearray)) else a.encode())
    return h.digest()


def _xor_half(b: bytes) -> bytes:
    half = len(b) // 2
    return bytes(b[i] ^ b[half + i] for i in range(half))


def _aes_ecb_encrypt(key: bytes, data: bytes) -> bytes:
    return AES.new(key, AES.MODE_ECB).encrypt(data)


def _rsa_encrypt(nvalue: str, evalue: str, session_key: str,
                 email: str, pw: str) -> str:
    message = (
        chr(len(session_key)) + session_key
        + chr(len(email)) + email
        + chr(len(pw)) + pw
    ).encode("utf-8")
    pub = rsa.PublicKey(int(nvalue, 16), int(evalue, 16))
    return binascii.hexlify(rsa.encrypt(message, pub)).decode()


def _curve25519_keypair() -> tuple:
    private_key = X25519PrivateKey.from_private_bytes(os.urandom(32))
    priv = private_key.private_bytes_raw()
    pub  = private_key.public_key().public_bytes_raw()
    return priv, pub


def _curve25519_shared(my_priv: bytes, their_pub: bytes) -> bytes:
    return X25519PrivateKey.from_private_bytes(my_priv).exchange(
        X25519PublicKey.from_public_bytes(their_pub)
    )


# ---------------------------------------------------------------------------
# Legy (LINE body-encryption proxy) helpers
# ---------------------------------------------------------------------------

_LEGY_IV = bytes([78,9,72,62,56,245,255,114,128,18,123,158,251,92,45,51])
_LEGY_PUBKEY = (
    "-----BEGIN PUBLIC KEY-----\n"
    "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0LRokSkGDo8G5ObFfyKi"
    "IdPAU5iOpj+UT+A3AcDxLuePyDt8IVp9HpOsJlf8uVk3Wr9fs+8y7cnF3WiY6Ro5"
    "26hy3fbWR4HiD0FaIRCOTbgRlsoGNC2rthp2uxYad5up78krSDXNKBab8t1PteCm"
    "Oq84TpDCRmainaZQN9QxzaSvYWUICVv27Kk97y2j3LS3H64NCqjS88XacAieivEL"
    "fMr6rT2GutRshKeNSZOUR3YROV4THa77USBQwRI7ZZTe6GUFazpocTN58QY8jFYO"
    "Dzfhdyoiym6rXJNNnUKatiSC/hmzdpX8/h4Y98KaGAZaatLAgPMRCe582q4JwHg7"
    "rwIDAQAB\n"
    "-----END PUBLIC KEY-----"
)
_LEGY_RSA_PUB = _CryptoRSA.import_key(_LEGY_PUBKEY)


def _legy_make_xcs(aes_key: bytes) -> str:
    """RSA-OAEP encrypt aes_key → x-lcs header value."""
    enc = _PKCS1_OAEP.new(_LEGY_RSA_PUB).encrypt(aes_key)
    return "0005" + base64.b64encode(enc).decode()


def _legy_enc_headers(headers: dict) -> bytes:
    """Encode inner headers in legy wire format."""
    body = bytearray()
    body += len(headers).to_bytes(2, "big")
    for k, v in headers.items():
        kb, vb = k.encode(), v.encode()
        body += len(kb).to_bytes(2, "big") + kb
        body += len(vb).to_bytes(2, "big") + vb
    return len(body).to_bytes(2, "big") + bytes(body)


def _legy_dec_headers(data: bytes):
    """Decode legy inner headers. Returns (headers_dict, thrift_body)."""
    total = int.from_bytes(data[0:2], "big") + 2
    n     = int.from_bytes(data[2:4], "big")
    pos   = 4
    hdrs  = {}
    for _ in range(n):
        kl = int.from_bytes(data[pos:pos+2], "big"); pos += 2
        k  = data[pos:pos+kl].decode();              pos += kl
        vl = int.from_bytes(data[pos:pos+2], "big"); pos += 2
        v  = data[pos:pos+vl].decode();              pos += vl
        hdrs[k] = v
    return hdrs, data[total:]


def _legy_sig(aes_key: bytes, encrypted: bytes) -> bytes:
    """4-byte xxhash MAC used in le=18."""
    r = [aes_key[i] ^ 92 for i in range(16)]
    n = xxhash.xxh32(seed=0)
    s = xxhash.xxh32(seed=0)
    n.update(bytes(r))
    for i in range(16):
        r[i] ^= 106
    s.update(bytes(r))
    s.update(encrypted)
    n.update(bytes.fromhex(s.hexdigest()))
    return bytes.fromhex(n.hexdigest())


def _legy_encrypt(aes_key: bytes, path: str, body: bytes,
                  auth_token: Optional[str] = None) -> bytes:
    inner_hdrs = {"x-lpqs": path}
    if auth_token:
        inner_hdrs["x-lt"] = auth_token
    payload   = _legy_enc_headers(inner_hdrs) + body
    encrypted = AES.new(aes_key, AES.MODE_CBC, iv=_LEGY_IV).encrypt(pad(payload, 16))
    return encrypted + _legy_sig(aes_key, encrypted)


def _legy_decrypt(aes_key: bytes, data: bytes):
    """Returns (inner_headers, thrift_body)."""
    data      = pad(data, 16)                                    # align if needed
    plaintext = AES.new(aes_key, AES.MODE_CBC, iv=_LEGY_IV).decrypt(data)[:-16]
    plaintext = unpad(plaintext, 16)
    return _legy_dec_headers(plaintext)


def _legy_headers(aes_key: bytes,
                  auth_token: Optional[str] = None) -> Dict[str, str]:
    h = {
        "x-line-application": _APP_NAME,
        "X-Line-Chrome-Version": "3.6.0",
        "x-le":  "18",
        "x-lcs": _legy_make_xcs(aes_key),
        "x-lap": "5",
        "x-lpv": "1",
        "x-lhm": "POST",
        "x-lal": "ja_JP",
        "User-Agent":   _USER_AGENT,
        "content-type": "application/x-thrift; protocol=TBINARY",
        "accept-encoding": "gzip, deflate",
    }
    if auth_token:
        h["X-Line-Access"] = auth_token
    return h


# ---------------------------------------------------------------------------
# End of legy helpers
# ---------------------------------------------------------------------------


def _encrypt_device_secret(server_pub: bytes, my_priv: bytes,
                            enc_key_chain: bytes) -> bytes:
    shared = _curve25519_shared(my_priv, server_pub)
    aes_key = _sha256(shared, b"Key")
    payload = _xor_half(_sha256(enc_key_chain))
    return _aes_ecb_encrypt(aes_key, payload)


def _i32be(n: int) -> bytes:
    """4-byte big-endian signed int, as LINE encodes key ids inside the AAD."""
    return struct.pack("!i", int(n))


def _e2ee_key_dir(save_path: str) -> str:
    d = os.path.join(save_path, ".e2eeKeys")
    os.makedirs(d, exist_ok=True)
    return d


def _as_bytes(v: Any) -> bytes:
    """Thrift BINARY fields come back as str when they happen to be valid UTF-8."""
    return v.encode("utf-8") if isinstance(v, str) else bytes(v)


def _decrypt_key_chain(server_pub: bytes, my_priv: bytes,
                       enc_key_chain: bytes) -> tuple:
    """Decrypt the E2EE key chain handed back by the primary device.

    Returns (private_key, public_key) for this device's E2EE identity.

    This also proves the Curve25519 exchange is correct: the same shared secret
    feeds _encrypt_device_secret, and the server has no way to tell us *why* it
    rejected a device secret.  If the shared secret were wrong this decrypts to
    noise and fails to parse as Thrift, which localises the fault immediately.
    """
    shared  = _curve25519_shared(my_priv, server_pub)
    aes_key = _sha256(shared, b"Key")
    aes_iv  = _xor_half(_sha256(shared, b"IV"))
    plain   = AES.new(aes_key, AES.MODE_CBC, aes_iv).decrypt(enc_key_chain)
    entry   = unpack_compact_struct(plain)[1][0]
    return _as_bytes(entry[5]), _as_bytes(entry[4])


# ---------------------------------------------------------------------------
# Token cache helpers
# ---------------------------------------------------------------------------

def _token_path(save_path: str, token: str) -> str:
    fn = md5(token.encode()).hexdigest()
    d = os.path.join(save_path, ".tokens")
    os.makedirs(d, exist_ok=True)
    return os.path.join(d, fn)


def _cert_path(save_path: str, email: str) -> str:
    d = os.path.join(save_path, ".data")
    os.makedirs(d, exist_ok=True)
    return os.path.join(d, f"{email}.crt")


def _refresh_path(save_path: str, token: str) -> str:
    fn = md5(token.encode()).hexdigest()
    d = os.path.join(save_path, ".refreshToken")
    os.makedirs(d, exist_ok=True)
    return os.path.join(d, fn)


def _read_file(path: str) -> Optional[str]:
    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as f:
            return f.read()
    return None


def _write_file(path: str, data: str) -> None:
    with open(path, "w", encoding="utf-8") as f:
        f.write(data)


# ---------------------------------------------------------------------------
# HTTP helpers
# ---------------------------------------------------------------------------

def _base_headers(auth_token: Optional[str] = None) -> Dict[str, str]:
    h = {
        "x-line-application": _APP_NAME,
        "X-Line-Chrome-Version": "3.6.0",
        "User-Agent": _USER_AGENT,
        "content-type": "application/x-thrift; protocol=TCOMPACT",
        "x-lal": "ja_JP",
        "x-lap": "5",
        "x-lpv": "1",
        "x-lhm": "POST",
        "accept-encoding": "gzip, deflate",
    }
    if auth_token:
        h["X-Line-Access"] = auth_token
    return h


def _auth_headers() -> Dict[str, str]:
    h = _base_headers()
    h["content-type"] = "application/x-thrift; protocol=TBINARY"
    return h


def _check(data: Any, *keys) -> Any:
    """Safe nested get from dict using integer or string keys."""
    cur = data
    for k in keys:
        if cur is None:
            return None
        if isinstance(cur, dict):
            cur = cur.get(k)
        else:
            return None
    return cur


# ---------------------------------------------------------------------------
# Main client
# ---------------------------------------------------------------------------

class LINE:
    """
    Minimal LINE client using the CHROMEOS (Chrome Extension) device type.

    Parameters
    ----------
    email : str, optional
        LINE account email address.
    password : str, optional
        LINE account password.
    auth_token : str, optional
        Skip login and use this token directly.
    save_path : str, optional
        Directory to cache tokens and certificates. Defaults to the directory
        containing the line_chrome package, so the cache is found no matter
        which directory the script is launched from.
    pincode : str, optional
        Fixed pincode shown during E2EE device confirmation (6 digits).
        Default is "202202". You must confirm this on your primary device.
    """

    def __init__(
        self,
        email: Optional[str] = None,
        password: Optional[str] = None,
        auth_token: Optional[str] = None,
        save_path: Optional[str] = None,
        pincode: str = "202202",
    ):
        self.save_path = (
            os.path.abspath(save_path) if save_path else _DEFAULT_SAVE_PATH
        )
        self.pincode = pincode.encode()
        self.auth_token: Optional[str] = None
        self._refresh_token: Optional[str] = None
        self._revision: int = 0
        self._global_rev: int = 0
        self._individual_rev: int = 0
        self._req_id: int = 0
        self._message_handlers: List[Callable] = []
        self._op_handlers: List[Callable] = []
        # Populated during E2EE login; needed to read/write letter-sealed chats.
        self.e2ee_key: Optional[Dict[str, Any]] = None
        self._e2ee_peer_cache: Dict[str, Dict] = {}
        self._e2ee_pub_cache: Dict[int, bytes] = {}
        self._e2ee_group_cache: Dict[str, Dict[str, Any]] = {}
        self._obs_token: Optional[str] = None

        self._http      = httpx.Client(http2=True, timeout=30)
        self._poll_http = httpx.Client(http2=True, timeout=185)
        self._legy_http = _requests.Session()   # HTTP/1.1 like CHRLINE

        if auth_token:
            self.auth_token = auth_token
            self._try_load_refresh_token()
        elif email and password:
            self._login_email(email, password)
        else:
            raise ValueError("Provide email+password or auth_token.")

        if self.auth_token:
            self._verify_login()

    # ------------------------------------------------------------------
    # Internal counters
    # ------------------------------------------------------------------

    def _next_req_id(self) -> int:
        self._req_id += 1
        return self._req_id

    # ------------------------------------------------------------------
    # HTTP request wrappers
    # ------------------------------------------------------------------

    def _post_legy(self, endpoint: str, data: bytes,
                   decode: str = "binary", lhm: str = "POST",
                   inner_token: Optional[str] = None,
                   timeout: float = 30) -> Any:
        """Send a request through the legy AES-CBC encryption proxy.

        decode='binary' → TBinary Thrift parse
        decode='compact' → TCompact Thrift parse
        decode='json' → json.loads on the inner body
        """
        aes_key = os.urandom(16)
        body    = _legy_encrypt(aes_key, endpoint, data, auth_token=inner_token)
        url     = _HOST_LEGY + _EP_LEGY
        h       = _legy_headers(aes_key)
        h["x-lhm"] = lhm
        r = self._legy_http.post(url, data=body, headers=h, timeout=timeout)
        if r.status_code != 200:
            raise RuntimeError(
                f"Legy HTTP {r.status_code} for {endpoint}: {r.content[:200]}"
            )
        if not r.content:
            raise RuntimeError(
                f"Legy empty response for {endpoint} (x-lcr={r.headers.get('x-lcr')})"
            )
        try:
            _, inner_body = _legy_decrypt(aes_key, r.content)
        except Exception as e:
            raise RuntimeError(
                f"Legy decrypt failed for {endpoint}: {e}  raw={r.content.hex()}"
            ) from e
        if decode == "json":
            return json.loads(inner_body) if inner_body else {}
        fn = unpack_binary if decode == "binary" else unpack_compact
        return fn(inner_body)

    def _post_talk(self, endpoint: str, data: bytes,
                   decode: str = "compact",
                   timeout: Optional[float] = None) -> Dict:
        url = _HOST_GW + endpoint
        h = _base_headers(self.auth_token)
        conn = self._poll_http if timeout and timeout > 60 else self._http
        r = conn.post(url, content=data, headers=h, timeout=timeout)
        # Handle token rotation
        next_tok = r.headers.get("x-line-next-access")
        if next_tok:
            self._rotate_token(next_tok)
        r.raise_for_status()
        if not r.content:
            return {}
        fn = unpack_binary if decode == "binary" else unpack_compact
        return fn(r.content)

    def _post_poll(self, endpoint: str, data: bytes, timeout: float = 120) -> Any:
        url = _HOST_GW + endpoint
        h = _base_headers(self.auth_token)
        h["x-las"] = "F"
        h["x-lam"] = "w"
        try:
            r = self._poll_http.post(url, content=data, headers=h, timeout=timeout)
        except httpx.ReadTimeout:
            return []
        next_tok = r.headers.get("x-line-next-access")
        if next_tok:
            self._rotate_token(next_tok)
        if not r.content:
            return []
        result = unpack_compact(r.content)
        if isinstance(result, dict) and "error" in result:
            raise LineServiceError(result["error"].get("code", 0),
                                   result["error"].get("message", ""))
        if isinstance(result, list):
            return result
        # fetchOps returns a list at field 0
        return result.get(0, result) if isinstance(result, dict) else []

    # ------------------------------------------------------------------
    # Token management
    # ------------------------------------------------------------------

    def _rotate_token(self, new_token: str) -> None:
        if self.auth_token:
            _write_file(_token_path(self.save_path, self.auth_token), new_token)
        self.auth_token = new_token
        print(f"[LINE] Token rotated → {new_token[:20]}…")

    def _try_load_refresh_token(self) -> None:
        if self.auth_token:
            data = _read_file(_refresh_path(self.save_path, self.auth_token))
            if data:
                self._refresh_token = data

    def refresh_token(self) -> bool:
        """Use the stored refresh token to get a new access token."""
        if not self._refresh_token:
            raise ValueError("No refresh token available.")
        params = [[11, 1, self._refresh_token]]
        data = pack_compact("refreshAccessToken", params)
        res = self._post_talk("/ACCT/authfactor/token/refresh/v1", data)
        new_token = _check(res, 1)
        new_refresh = _check(res, 5)
        if not new_token:
            raise RuntimeError("Token refresh failed.")
        old = self.auth_token
        self.auth_token = new_token
        self._refresh_token = new_refresh or self._refresh_token
        if old:
            _write_file(_token_path(self.save_path, old), new_token)
        if new_refresh and new_token:
            _write_file(_refresh_path(self.save_path, new_token), new_refresh)
        print(f"[LINE] Token refreshed.")
        return True

    # ------------------------------------------------------------------
    # Login
    # ------------------------------------------------------------------

    def _get_rsa_key_info(self) -> Dict:
        params = [[8, 2, 1]]   # provider=LINE
        data = pack_binary("getRSAKeyInfo", params)
        return self._post_legy(_EP_TALK_DO, data)

    def _login_v2(self, keynm, enc_data, secret=None, device_name=SYSTEM_MODEL,
                  cert=None, verifier=None, called_name="loginV2") -> Dict:
        # verifier takes precedence (loginType=QRCODE=1) even when secret is also set
        login_type = 1 if verifier else (2 if secret else 0)
        params = [[
            12, 2, [
                [8,  1, login_type],
                [8,  2, 1],           # provider=LINE
                [11, 3, keynm],
                [11, 4, enc_data],
                [2,  5, False],
                [11, 6, ""],
                [11, 7, device_name],
                [11, 8, cert],
                [11, 9, verifier],
                [11, 10, secret],
                [8,  11, 1],
                [11, 12, SYSTEM_MODEL],
            ]
        ]]
        data = pack_binary(called_name, params)
        return self._post_legy(_EP_RS, data)

    def _check_pin_code_v2(self, access_session: str) -> Dict:
        """Long-poll until the user confirms the pincode.

        /LF1 is a plain HTTPS GET to legy.line-apps.com (not gf/gwz, not
        through legy encryption).  CANCEL events are stale; consume and retry.
        """
        url = "https://legy.line-apps.com/LF1"
        h = {
            "x-line-application": _APP_NAME,
            "X-Line-Access":      access_session,
            "x-lal":              "ja_JP",
            "x-lpv":              "1",
            "x-lhm":              "GET",
            "User-Agent":         _USER_AGENT,
            "accept":             "application/x-thrift",
            "accept-encoding":    "gzip",
        }
        for attempt in range(6):
            print(f"[DBG] /LF1 attempt {attempt + 1} → {url}")
            try:
                r = self._poll_http.get(url, headers=h, timeout=180)
                print(f"[DBG] /LF1 status={r.status_code} raw={r.content[:300]}")
                if r.status_code != 200 or not r.content:
                    break
                result   = json.loads(r.content)
                meta     = (result.get("result") or {}).get("metadata") or {}
                err_code = meta.get("errorCode")
                if err_code == "CANCEL":
                    print(f"[DBG] /LF1 CANCEL (stale, attempt {attempt + 1}), retrying…")
                    time.sleep(1)
                    continue
                return result.get("result") or {}
            except Exception as e:
                print(f"[DBG] /LF1 error: {type(e).__name__}: {e}")
                break
        return {}

    def _confirm_e2ee_login(self, verifier: str, device_secret: bytes) -> str:
        params = [
            [11, 1, verifier],
            [11, 2, device_secret],  # raw bytes — Thrift BINARY, not base64 string
        ]
        data = pack_binary("confirmE2EELogin", params)
        res = self._post_legy(_EP_RS, data)
        print(f"[DBG] confirmE2EELogin res={res}")
        # Thrift returns a method's result in field 0.  unpack_binary only wraps
        # it as {0: value} for scalars — a struct result comes back unwrapped,
        # hence the field-1 fallback.
        return res.get(0) or res.get(1) or ""

    def _login_email(self, email: str, password: str) -> None:
        # 1. Fetch RSA public key
        rsa_info = self._get_rsa_key_info()
        keynm       = rsa_info.get(1, "")
        nvalue      = rsa_info.get(2, "")
        evalue      = rsa_info.get(3, "")
        session_key = rsa_info.get(4, "")
        print(f"[DBG] getRSAKeyInfo keynm={keynm!r}")

        # 2. RSA-encrypt credentials
        enc_data = _rsa_encrypt(nvalue, evalue, session_key, email, password)

        # 3. Generate Curve25519 keypair for E2EE device registration
        priv_key, pub_key = _curve25519_keypair()

        # 4. Encrypt public key with AES-ECB(SHA256(pincode)) for the secret field.
        #    Field 10 is Thrift BINARY: the primary device decrypts these bytes with
        #    SHA256(pincode) to recover pub_key.  Must be sent RAW — base64-encoding
        #    it here makes the phone decrypt garbage and report "unknown error".
        aes_key = _sha256(self.pincode)
        secret  = _aes_ecb_encrypt(aes_key, pub_key)

        # 5. Load stored cert if available (skips PIN on already-trusted device)
        cert = _read_file(_cert_path(self.save_path, email))

        try:
            res = self._login_v2(keynm, enc_data, secret=secret,
                                 device_name=SYSTEM_MODEL, cert=cert)
        except LineServiceError as e:
            if e.code == 89:
                # Server refuses E2EE secret — fall back without it
                secret = None
                res = self._login_v2(keynm, enc_data, secret=None,
                                     device_name=SYSTEM_MODEL, cert=cert)
            else:
                raise

        print(f"[DBG] loginV2 (1st) res={res}")

        # 6. If V3 token already in res[9], we're done (trusted device)
        token_info = res.get(9)
        if token_info is None:
            verifier = res.get(3, "")
            print(f"[LINE] Confirm pincode '{self.pincode.decode()}' on your primary device…")

            e2ee_info = self._check_pin_code_v2(verifier)
            print(f"[DBG] /LF1 e2ee_info={e2ee_info}")
            if not e2ee_info:
                raise RuntimeError("Pincode confirmation timed out or returned empty.")

            # 7. Build device secret from the E2EE key chain
            metadata    = e2ee_info.get("metadata") or e2ee_info
            # errorCode is always present and carries the outcome — "SUCCESS" on
            # approval, "CANCEL"/etc. on rejection.  It is not an error flag.
            error_code  = metadata.get("errorCode")
            if error_code not in (None, "SUCCESS"):
                raise RuntimeError(
                    f"PIN confirmation rejected by primary device: {error_code}. "
                    "Please run again and tap APPROVE (not cancel) on your phone."
                )
            pub_key_b64 = metadata.get("publicKey", "")
            enc_kc_b64  = metadata.get("encryptedKeyChain", "")
            if not pub_key_b64 or not enc_kc_b64:
                raise RuntimeError(f"PIN confirmed but E2EE key data missing: {metadata}")
            print(f"[DBG] E2EE publicKey={pub_key_b64[:20]}… encKC={enc_kc_b64[:20]}…")

            server_pub    = base64.b64decode(pub_key_b64)
            enc_kc        = base64.b64decode(enc_kc_b64)

            # Decrypt the key chain first — it shares the Curve25519 secret with
            # the device secret below, so a failure here pins the fault on the
            # key exchange rather than on the confirmE2EELogin call.
            try:
                e2ee_priv, e2ee_pub = _decrypt_key_chain(server_pub, priv_key, enc_kc)
            except Exception as e:
                raise RuntimeError(
                    f"E2EE key chain failed to decrypt ({type(e).__name__}: {e}). "
                    "The Curve25519 shared secret is wrong, so the device secret "
                    "would be rejected too."
                ) from e
            key_id  = int(metadata.get("keyId", 0))
            version = int(metadata.get("e2eeVersion", 1))
            # Persist immediately: this key is only ever handed out here, and
            # losing it costs another device confirmation.  Our own mid is not
            # known until _verify_login, so this lands under key_<id>.json.
            self._save_e2ee_key(e2ee_priv, e2ee_pub, key_id, version)
            self.e2ee_key = {
                "keyId":       key_id,
                "privKey":     base64.b64encode(e2ee_priv).decode(),
                "pubKey":      base64.b64encode(e2ee_pub).decode(),
                "e2eeVersion": version,
            }
            print(f"[DBG] key chain OK — shared secret verified "
                  f"(keyId={self.e2ee_key['keyId']}, "
                  f"priv={len(e2ee_priv)}b, pub={len(e2ee_pub)}b)")

            device_secret = _encrypt_device_secret(server_pub, priv_key, enc_kc)
            print(f"[DBG] device_secret={device_secret.hex()} ({len(device_secret)}b)")

            # 8. Confirm E2EE login → get a new verifier
            e2ee_verifier = self._confirm_e2ee_login(verifier, device_secret)
            print(f"[DBG] e2ee_verifier={e2ee_verifier!r}")
            if not e2ee_verifier:
                raise RuntimeError("confirmE2EELogin returned empty verifier.")

            # 9. Final loginV2 — re-send original keynm/enc_data/secret/cert + new verifier
            res = self._login_v2(keynm, enc_data, secret=secret,
                                 device_name=SYSTEM_MODEL, cert=cert,
                                 verifier=e2ee_verifier)
            print(f"[DBG] loginV2 (2nd) res keys={list(res.keys())}")
            token_info = res.get(9)
            if token_info is None:
                raise RuntimeError(f"Login failed: no token in response. res={res}")

            # Save cert so future logins skip the PIN step.  Never let a caching
            # problem discard a login that just cost a device confirmation —
            # warn and carry on with the token we already hold.
            new_cert = res.get(2, "")
            if new_cert:
                try:
                    if isinstance(new_cert, bytes):
                        new_cert = new_cert.decode("utf-8", "surrogateescape")
                    _write_file(_cert_path(self.save_path, email), new_cert)
                    print("[LINE] Device cert saved — future logins skip the PIN.")
                except OSError as e:
                    print(f"[LINE] WARNING: could not save device cert ({e}). "
                          "Login succeeded, but the next run will ask for the PIN again.")

        access_token  = _check(token_info, 1)
        refresh_token = _check(token_info, 2)

        if not access_token:
            raise RuntimeError("Login failed: empty access token.")

        self.auth_token    = access_token
        self._refresh_token = refresh_token

        print(f"[LINE] Logged in. Token: {access_token[:20]}…")

        if refresh_token:
            try:
                _write_file(_refresh_path(self.save_path, access_token), refresh_token)
            except OSError as e:
                print(f"[LINE] WARNING: could not save refresh token ({e}).")

    def _verify_login(self) -> None:
        profile = self.get_profile()
        if not profile.mid:
            raise RuntimeError("Token verification failed: could not get profile.")
        self.mid = profile.mid
        print(f"[LINE] Authenticated as '{profile.display_name}' ({profile.mid})")
        # Now that the mid is known, settle the E2EE key under its mid-keyed
        # name so later sessions find it without a server round trip.
        if self.e2ee_key:
            self._save_e2ee_key(
                base64.b64decode(self.e2ee_key["privKey"]),
                base64.b64decode(self.e2ee_key["pubKey"]),
                self.e2ee_key["keyId"], self.e2ee_key["e2eeVersion"],
                mid=self.mid,
            )

    # ------------------------------------------------------------------
    # TalkService API
    # ------------------------------------------------------------------

    def get_profile(self) -> Profile:
        """Return own account profile."""
        data = pack_compact("getProfile", [])
        res = self._post_talk(_EP_TALK, data)
        return Profile.from_dict(res)

    def get_all_contact_ids(self) -> List[str]:
        """Return the MID of every contact.

        A 1:1 conversation is not a server-side room — it is addressed by the
        peer's own MID, so these double as the chat MIDs for direct chats.
        """
        data = pack_compact("getAllContactIds", [])
        res = self._post_talk(_EP_TALK, data)
        # Bare list result, so the decoder keys it at 0.
        return res.get(0) or []

    def get_chat_mids(self, include_peers: bool = False) -> List[str]:
        """Return every chat MID.

        getAllChatMids only covers groups and rooms — chats you are a member of
        plus ones you have been invited to.  Direct 1:1 chats are not included,
        because LINE has no room object for them; pass include_peers=True to
        append one MID per contact.
        """
        res  = self._get_all_chat_mids()
        mids = list(res.get(1) or []) + list(res.get(2) or [])
        if include_peers:
            mids += self.get_all_contact_ids()
        return mids

    def get_chats(self, mids: Optional[List[str]] = None,
                  with_members: bool = True,
                  include_peers: bool = False,
                  chunk_size: int = 200) -> List[Chat]:
        """
        Return list of chats.

        If mids is None the list is discovered via get_chat_mids(); by default
        that is groups and rooms only.  Set include_peers=True to also return
        1:1 chats, one per contact.

        Requests are chunked because getChats takes the whole MID list in one
        struct and a large friend list will otherwise overflow the request.
        """
        if mids is None:
            mids = self.get_chat_mids(include_peers=include_peers)
        if not mids:
            return []

        chats: List[Chat] = []
        for i in range(0, len(mids), chunk_size):
            batch = mids[i:i + chunk_size]
            params = [
                [12, 1, [
                    [15, 1, [11, batch]],
                    [2, 2, with_members],
                ]]
            ]
            data = pack_compact("getChats", params)
            res = self._post_talk(_EP_TALK, data)
            # GetChatsResponse: {1: chats}.  Already unwrapped by unpack_compact.
            raw_list = res.get(1) or []
            chats += [Chat.from_dict(c) for c in raw_list if isinstance(c, dict)]
        return chats

    def _get_all_chat_mids(self) -> Dict:
        params = [
            [12, 1, [
                [2, 1, True],   # withMemberChats
                [2, 2, True],   # withInvitedChats
            ]]
        ]
        data = pack_compact("getAllChatMids", params)
        # unpack_compact already unwraps the Thrift success struct, so this is
        # GetAllChatMidsResponse itself: {1: memberChatMids, 2: invitedChatMids}.
        return self._post_talk(_EP_TALK, data)

    def get_contact(self, mid: str) -> Contact:
        """Profile of a single user.

        Works for anyone whose MID you know, not just friends — a group member
        you have never added resolves fine, though fields like statusMessage
        may be blank depending on their privacy settings.
        """
        data = pack_compact("getContact", [[11, 2, mid]])
        # getContact returns a Contact at success field 0, which the decoder
        # hands back unwrapped: the response *is* the contact.
        return Contact.from_dict(self._post_talk(_EP_TALK, data))

    def find_contact_by_userid(self, user_id: str) -> Contact:
        """Look a user up by their LINE ID (the @handle), not their MID.

        Not present in the generated Thrift definitions, so the response shape
        is inferred from the other findContact* calls; treat it as unverified.
        """
        data = pack_compact("findContactByUserid", [[11, 2, user_id]])
        return Contact.from_dict(self._post_talk(_EP_TALK, data))

    def get_contacts(self, mids: List[str],
                     chunk_size: int = 200) -> List[Contact]:
        """Return contacts for the given MIDs."""
        out: List[Contact] = []
        for i in range(0, len(mids), chunk_size):
            params = [
                [12, 1, [
                    [15, 1, [11, mids[i:i + chunk_size]]],
                ]]
            ]
            data = pack_compact("getContactsV2", params)
            res = self._post_talk(_EP_TALK, data)
            # GetContactsV2Response{1: map<mid, ContactEntry>} — the Contact
            # itself is nested at ContactEntry field 3, not the entry root.
            raw = _check(res, 1)
            if not isinstance(raw, dict):
                continue
            for entry in raw.values():
                if isinstance(entry, dict):
                    contact = entry.get(3)
                    if isinstance(contact, dict):
                        out.append(Contact.from_dict(contact))
        return out

    def send_message(
        self,
        to: str,
        text: str,
        content_type: int = 0,
        content_metadata: Optional[Dict[str, str]] = None,
        reply_to: Optional[str] = None,
        e2ee: Optional[bool] = None,
    ) -> SendMessageResult:
        """
        Send a text message to a chat or user.

        Parameters
        ----------
        to : str
            Target MID. Prefix indicates type:
            'u' = user, 'c' = group chat, 'r' = room.
        text : str
            Message text.
        content_type : int
            0=TEXT, 7=STICKER, 9=GIFT, 13=CONTACT, 15=LOCATION
        reply_to : str, optional
            Message ID to reply to.
        e2ee : bool, optional
            Force letter sealing on or off.  The default (None) sends plain
            and, if the server answers E2EE_RETRY_ENCRYPT (82), transparently
            re-sends the message encrypted — which is what LINE's own clients
            do, since whether a chat is sealed is the recipient's setting and
            is not knowable up front.
        """
        if content_metadata is None:
            content_metadata = {}

        def build(sealed: Optional[tuple]) -> bytes:
            meta = dict(content_metadata)
            message = [
                [11, 2,  to],
                [10, 5,  0],        # createdTime
                [10, 6,  0],        # deliveredTime
                [2,  14, False],    # hasContent
                [8,  15, content_type],
                [3,  19, 0],        # sessionId
            ]
            if sealed is None:
                message.append([11, 10, text])
            else:
                chunks, spec_version = sealed
                # Must match the version baked into the AAD, not a constant.
                meta.update({"e2eeVersion": str(spec_version),
                             "contentType": str(content_type),
                             "e2eeMark":    "2"})
                message.append([15, 20, [11, chunks]])
            message.append([13, 18, [11, 11, meta]])
            if reply_to is not None:
                message += [
                    [11, 21, reply_to],
                    [8,  22, 3],    # messageRelationType = REPLY
                    [8,  24, 1],    # relatedMessageServiceCode
                ]
            return pack_compact("sendMessage", [
                [8,  1, self._next_req_id()],
                [12, 2, message],
            ])

        if e2ee:
            res = self._post_talk(_EP_TALK, build(self._encrypt_e2ee_text(
                to, text, content_type)))
            return SendMessageResult.from_dict(res)

        try:
            res = self._post_talk(_EP_TALK, build(None))
        except LineServiceError as err:
            # 82 = E2EE_RETRY_ENCRYPT: the chat is letter-sealed.
            if err.code != 82 or e2ee is False:
                raise
            print(f"[LINE] {to} is letter-sealed; re-sending encrypted.")
            res = self._post_talk(_EP_TALK, build(self._encrypt_e2ee_text(
                to, text, content_type)))
        return SendMessageResult.from_dict(res)

    # ------------------------------------------------------------------
    # Object storage (images / files)
    # ------------------------------------------------------------------

    def _obs_access_token(self) -> str:
        """Access token for OBS uploads.

        CHROMEOS sends the plain auth token; every other device type must
        exchange it via acquireEncryptedAccessToken and use the part after the
        \\x1e separator.  We identify as DESKTOPWIN, so we take that path.
        """
        if self._obs_token:
            return self._obs_token
        if APP_TYPE == "CHROMEOS":
            self._obs_token = self.auth_token or ""
            return self._obs_token
        res = self._post_talk(_EP_TALK,
                              pack_compact("acquireEncryptedAccessToken",
                                           [[8, 2, 2]]))   # featureType=OBS
        token = res.get(0) or ""
        if not token:
            raise RuntimeError(f"acquireEncryptedAccessToken returned nothing: {res}")
        self._obs_token = token.split("\x1e")[1] if "\x1e" in token else token
        return self._obs_token

    def send_media(self, to: str, media: Union[str, bytes],
                   media_type: str = "image",
                   name: Optional[str] = None,
                   duration: Optional[int] = None) -> Dict[str, str]:
        """Send an image, video, audio clip or file.

        media may be a path, a URL, or raw bytes.  There is no "attach a file
        to a message" call: the upload itself creates the message, because the
        recipient travels in the upload params as `tomid`.  Returns the object
        id and hash.

        duration (milliseconds) applies to video and audio.  CHRLINE sets it
        when forwarding but never on a direct upload, so whether LINE needs it
        here is unconfirmed — pass it if the clip length shows up wrong.

        Only handles chats that accept unencrypted media.  A letter-sealed
        chat needs the encrypted-media flow, which is not implemented — LINE
        rejects plain uploads there the same way it rejects plain text.
        """
        if media_type not in ("image", "video", "audio", "file", "gif"):
            raise ValueError(f"Unsupported media type: {media_type}")

        if isinstance(media, str):
            if media.startswith("http"):
                data = self._http.get(media).content
                name = name or media.rsplit("/", 1)[-1]
            else:
                with open(media, "rb") as f:
                    data = f.read()
                name = name or os.path.basename(media)
        else:
            data = bytes(media)
        if not data:
            raise ValueError("No media data to send.")
        name = name or f"{int(time.time())}"

        params = {
            "type":   media_type,
            "ver":    "2.0",
            "name":   name,
            "oid":    "reqseq",
            "reqseq": str(self._next_req_id()),
            "tomid":  to,
        }
        if media_type == "gif":
            params["type"] = "image"
            params["cat"] = "original"
        if duration is not None:
            params["duration"] = str(int(duration))
        headers = {
            "User-Agent":          _USER_AGENT,
            "X-Line-Application":  _APP_NAME,
            "X-Line-Access":       self._obs_access_token(),
            "X-Line-Mid":          getattr(self, "mid", "") or "",
            "x-lal":               "ja_JP",
            "Content-Type":        "application/octet-stream",
            "Content-Length":      str(len(data)),
            "X-Obs-Params":        base64.b64encode(
                                       json.dumps(params).encode()).decode(),
        }
        r = self._http.post(_HOST_GW + _EP_OBS, content=data,
                            headers=headers, timeout=120)
        # 200 = already cached on the OBS server, 201 = newly created.
        if r.status_code not in (200, 201):
            raise RuntimeError(
                f"OBS upload failed: HTTP {r.status_code} {r.content[:200]!r}"
            )
        return {"objectId": r.headers.get("x-obs-oid", ""),
                "hash":     r.headers.get("x-obs-hash", "")}

    def send_image(self, to: str, image: Union[str, bytes],
                   name: Optional[str] = None) -> Dict[str, str]:
        """Send an image. Thin wrapper over send_media()."""
        return self.send_media(to, image, "image", name)

    def send_video(self, to: str, video: Union[str, bytes],
                   name: Optional[str] = None,
                   duration: Optional[int] = None) -> Dict[str, str]:
        """Send a video. duration is in milliseconds."""
        return self.send_media(to, video, "video", name, duration)

    def send_file(self, to: str, file: Union[str, bytes],
                  name: Optional[str] = None) -> Dict[str, str]:
        """Send an arbitrary file."""
        return self.send_media(to, file, "file", name)

    def _decrypt_key_material(self, data: bytes, key_material: Any) -> bytes:
        """Undo the file encryption used for media in sealed chats.

        HKDF-SHA256 over the key material yields encKey/macKey/nonce, then
        AES-CTR.  The uploader appends a 32-byte HMAC of the ciphertext; it is
        stripped only if it verifies, so this still works if a future server
        stops appending it.
        """
        if isinstance(key_material, str):
            key_material = base64.b64decode(key_material)
        t = HKDF(algorithm=hashes.SHA256(), length=32 + 32 + 12,
                 salt=None, info=b"FileEncryption").derive(key_material)
        enc_key, mac_key, nonce = t[:32], t[32:64], t[64:]

        if len(data) > 32:
            expected = hmac.new(mac_key, data[:-32], hashlib.sha256).digest()
            if hmac.compare_digest(expected, data[-32:]):
                data = data[:-32]
            else:
                print("[LINE] media MAC did not verify; decrypting as-is.")
        return AES.new(enc_key, AES.MODE_CTR, nonce=nonce).decrypt(data)

    @staticmethod
    def _talk_meta(message_id: str) -> str:
        """The X-Talk-Meta header proving we may fetch a sealed object.

        base64( json( {"message": base64( TBinary struct )} ) ), where the
        struct carries the message id at field 4 and an empty list at 27.
        """
        inner = pack_binary_struct([[11, 4, message_id], [15, 27, [12, []]]])
        payload = json.dumps({"message": base64.b64encode(inner).decode()})
        return base64.b64encode(payload.encode()).decode()

    def download_image(self, msg: Message, path: Optional[str] = None,
                       size: Optional[str] = None,
                       preview: bool = False) -> bytes:
        """Download the media attached to a message.

        Media lives in object storage, not inside the message.  In a sealed
        chat the object is encrypted and the message carries only the key
        material.

        preview=True fetches the thumbnail.  Plain and sealed media address it
        completely differently — a `/preview` path segment versus a separate
        `<OID>__ud-preview` object — so use this flag rather than building the
        URL yourself.

        size: a dimension string such as "m800x1200" or "w800".  Plain media
        only; sealed objects are not resizable server-side.
        """
        if not msg.id:
            raise ValueError("Message has no id, cannot locate its object.")
        if msg.content_type not in (ContentType.IMAGE, ContentType.VIDEO,
                                    ContentType.AUDIO, ContentType.FILE):
            raise ValueError(
                f"Message {msg.id} is content type {msg.content_type}, "
                "not a downloadable object."
            )

        headers = {
            "User-Agent":         _USER_AGENT,
            "X-Line-Application": _APP_NAME,
            "X-Line-Access":      self._obs_access_token(),
            "X-Line-Mid":         getattr(self, "mid", "") or "",
            "x-lal":              "ja_JP",
            "X-LHM":              "GET",
            "X-LPV":              "1",
        }

        # Sealed media does not live under talk/m keyed by message id: the
        # sender records the real namespace and object id in contentMetadata,
        # and the download must be authorised with an X-Talk-Meta header.
        meta = msg.content_metadata or {}
        oid, sid = meta.get("OID"), meta.get("SID")
        if msg.chunks and oid and sid:
            # The thumbnail is its own object, suffixed onto the id.
            if preview:
                oid += "__ud-preview"
            url = f"{_HOST_GW}/oa/r/talk/{sid}/{oid}"
            headers["X-Talk-Meta"] = self._talk_meta(msg.id)
            if size:
                print("[LINE] size is ignored for sealed media.")
        else:
            url = f"{_HOST_GW}/oa/r/talk/m/{msg.id}"
            if preview:
                url += "/preview"
            elif size:
                url += f"/{size}"
        r = self._http.get(url, headers=headers, timeout=120)
        if r.status_code != 200:
            raise RuntimeError(
                f"OBS download failed: HTTP {r.status_code} {r.content[:200]!r}"
            )
        data = r.content

        if msg.chunks:
            key_material = self._e2ee_payload(msg).get("keyMaterial")
            if not key_material:
                raise RuntimeError(
                    f"Sealed media message {msg.id} carried no keyMaterial."
                )
            data = self._decrypt_key_material(data, key_material)

        if path:
            with open(path, "wb") as f:
                f.write(data)
        return data

    def unsend_message(self, message_id: str) -> None:
        """Unsend a previously sent message."""
        params = [[11, 2, message_id]]
        data = pack_compact("unsendMessage", params)
        self._post_talk(_EP_TALK, data)

    def send_read_receipt(self, chat_mid: str, last_message_id: str) -> None:
        """Mark messages in a chat as read."""
        params = [
            [8,  1, self._next_req_id()],
            [11, 2, chat_mid],
            [11, 3, last_message_id],
        ]
        data = pack_compact("sendChatChecked", params)
        self._post_talk(_EP_TALK, data)

    # ------------------------------------------------------------------
    # E2EE (letter sealing)
    # ------------------------------------------------------------------

    def _save_e2ee_key(self, priv: bytes, pub: bytes, key_id: int,
                       version: int = 1, mid: Optional[str] = None) -> None:
        """Persist this device's E2EE identity key.

        Only obtainable from the key chain during a PIN login, so losing it
        means another device confirmation.  Written under both the mid and the
        key id, matching CHRLINE's layout.
        """
        data = json.dumps({
            "keyId":       int(key_id),
            "privKey":     base64.b64encode(priv).decode(),
            "pubKey":      base64.b64encode(pub).decode(),
            "e2eeVersion": int(version),
        })
        d = _e2ee_key_dir(self.save_path)
        names = [f"key_{int(key_id)}.json"] + ([f"{mid}.json"] if mid else [])
        for fn in names:
            try:
                _write_file(os.path.join(d, fn), data)
            except OSError as e:
                print(f"[LINE] WARNING: could not save E2EE key to {fn}: {e}")

    def _load_e2ee_key(self) -> Optional[Dict[str, Any]]:
        """Load this device's E2EE key, caching it on self.e2ee_key.

        Tries the mid-keyed file, then falls back to asking the server which
        key ids belong to us and looking for a matching local private key.
        """
        if self.e2ee_key:
            return self.e2ee_key
        d = _e2ee_key_dir(self.save_path)
        mid = getattr(self, "mid", None)

        raw = _read_file(os.path.join(d, f"{mid}.json")) if mid else None
        if not raw:
            # The login that stored the key did not know our mid yet, so the
            # file is named after the key id.  Ask the server for our key ids.
            try:
                for key in self.get_e2ee_public_keys():
                    kid = key.get(2)
                    if kid is None:
                        continue
                    raw = _read_file(os.path.join(d, f"key_{int(kid)}.json"))
                    if raw:
                        if mid:   # backfill so next time is a single read
                            _write_file(os.path.join(d, f"{mid}.json"), raw)
                        break
            except LineServiceError as e:
                print(f"[LINE] could not list E2EE public keys: {e}")
        if not raw:
            return None
        self.e2ee_key = json.loads(raw)
        return self.e2ee_key

    def get_e2ee_public_keys(self) -> List[Dict]:
        """Our own registered E2EE public keys."""
        data = pack_compact("getE2EEPublicKeys", [])
        res = self._post_talk(_EP_TALK, data)
        return res.get(0) or []

    def negotiate_e2ee_public_key(self, mid: str) -> Dict:
        """Fetch a peer's E2EE public key.

        Returns E2EENegotiationResult {2: publicKey{2: keyId, 4: keyData},
        3: specVersion}.  specVersion -1 means the peer cannot do E2EE.
        """
        if mid in self._e2ee_peer_cache:
            return self._e2ee_peer_cache[mid]
        params = [[11, 2, mid]]
        data = pack_compact("negotiateE2EEPublicKey", params)
        res = self._post_talk(_EP_TALK, data)
        self._e2ee_peer_cache[mid] = res
        return res

    def _encrypt_e2ee_text(self, to: str, text: str,
                           content_type: int = 0) -> tuple:
        """Encrypt a text message for a 1:1 chat, group or room.

        Returns (chunks, spec_version) where
        chunks = [salt, ciphertext+tag, nonce, senderKeyId, receiverKeyId].

        The version is returned rather than assumed because the recipient
        rebuilds the AAD from contentMetadata: if we sign with one version and
        advertise another, their GCM tag check fails and the message shows up
        as undecryptable on every device but ours.
        """
        self_key = self._load_e2ee_key()
        if not self_key:
            raise RuntimeError(
                "No E2EE key available. It is only handed out during a PIN "
                "login, so delete session.json and the .data/*.crt cert, then "
                "log in again to capture and store it."
            )

        sender_key_id = int(self_key["keyId"])

        if to.startswith(("c", "r")):
            # Groups do not do pairwise ECDH.  Everyone shares one group key
            # pair, so we pair the group's *private* half with our own public
            # half — the same secret every member derives, and the same one
            # _e2ee_payload computes when decrypting our own group messages.
            group = self._group_shared_key(to)
            priv            = base64.b64decode(group["privKey"])
            peer_key        = base64.b64decode(self_key["pubKey"])
            receiver_key_id = int(group["keyId"])
            # No negotiate call for groups: the shared key fixes the version.
            spec_version    = 2
        else:
            peer = self.negotiate_e2ee_public_key(to)
            spec_version = _check(peer, 3)
            if spec_version == -1:
                raise RuntimeError(f"{to} does not support E2EE.")
            spec_version = 2 if spec_version is None else int(spec_version)

            pub = _check(peer, 2) or {}
            receiver_key_id = _check(pub, 2)
            peer_key        = _as_bytes(_check(pub, 4) or b"")
            if receiver_key_id is None or len(peer_key) != 32:
                raise RuntimeError(f"Bad peer key for {to}: {peer}")

            priv = base64.b64decode(self_key["privKey"])

        shared  = _curve25519_shared(priv, peer_key)
        salt    = os.urandom(16)
        gcm_key = _sha256(shared, salt, b"Key")
        nonce   = os.urandom(16)
        aad = (to.encode() + self.mid.encode()
               + _i32be(sender_key_id) + _i32be(receiver_key_id)
               + _i32be(spec_version) + _i32be(content_type))
        payload = json.dumps({"text": text}).encode()
        enc = AESGCM(gcm_key).encrypt(nonce, payload, aad)

        chunks = [salt, enc, nonce,
                  _i32be(sender_key_id), _i32be(receiver_key_id)]
        return chunks, spec_version

    def _self_key_by_id(self, key_id: int) -> Optional[Dict[str, Any]]:
        raw = _read_file(os.path.join(_e2ee_key_dir(self.save_path),
                                      f"key_{int(key_id)}.json"))
        if raw:
            return json.loads(raw)
        key = self._load_e2ee_key()
        if key and int(key["keyId"]) == int(key_id):
            return key
        return None

    def _peer_public_key(self, mid: str, key_id: int) -> bytes:
        """A peer's E2EE public key for a specific key id.

        Cached on disk by key id, because negotiateE2EEPublicKey only ever
        returns the peer's *current* key — once they rotate, older messages
        become undecryptable unless their key was captured earlier.
        """
        if key_id in self._e2ee_pub_cache:
            return self._e2ee_pub_cache[key_id]

        d = os.path.join(self.save_path, ".e2eePublicKeys")
        os.makedirs(d, exist_ok=True)
        path = os.path.join(d, f"key_id_{int(key_id)}.json")
        raw = _read_file(path)
        if raw:
            key = base64.b64decode(json.loads(raw)["keyData"])
            self._e2ee_pub_cache[key_id] = key
            return key

        res = self.negotiate_e2ee_public_key(mid)
        pub  = _check(res, 2) or {}
        kid  = _check(pub, 2)
        data = _as_bytes(_check(pub, 4) or b"")
        if kid is None or len(data) != 32:
            raise RuntimeError(f"No usable E2EE key for {mid}: {res}")
        kid = int(kid)
        self._e2ee_pub_cache[kid] = data
        try:
            _write_file(os.path.join(d, f"key_id_{kid}.json"),
                        json.dumps({"mid": mid,
                                    "keyData": base64.b64encode(data).decode()}))
        except OSError:
            pass
        if kid != int(key_id):
            raise RuntimeError(
                f"{mid} has rotated E2EE keys: this message needs key "
                f"{key_id}, the server now offers {kid}."
            )
        return data

    def _group_shared_key(self, group_mid: str,
                          key_id: Optional[int] = None) -> Dict[str, Any]:
        """The group's shared Curve25519 private key.

        A sealed group does not do pairwise ECDH.  The group creator generates
        one key pair and hands each member an encrypted copy of the private
        half; every member then derives the same secret.  Our copy arrives
        wrapped with ECDH(our key, creator's key).
        """
        cached = self._e2ee_group_cache.get(group_mid)
        if cached and (key_id is None or int(cached["keyId"]) == int(key_id)):
            return cached

        d = os.path.join(self.save_path, ".e2eeGroupKeys")
        os.makedirs(d, exist_ok=True)
        path = os.path.join(d, f"{group_mid}.json")
        raw = _read_file(path)
        if raw:
            data = json.loads(raw)
            if key_id is None or int(data["keyId"]) == int(key_id):
                self._e2ee_group_cache[group_mid] = data
                return data

        params = [[8, 2, 2], [11, 3, group_mid]]   # keyVersion=2
        res = self._post_talk(_EP_TALK,
                              pack_compact("getLastE2EEGroupSharedKey", params))

        group_key_id  = _check(res, 2)
        creator       = _check(res, 3)
        creator_kid   = _check(res, 4)
        receiver_kid  = _check(res, 6)
        encrypted     = _as_bytes(_check(res, 7) or b"")
        if not encrypted or creator is None:
            raise RuntimeError(f"No group shared key for {group_mid}: {res}")

        self_key = self._self_key_by_id(receiver_kid)
        if not self_key:
            raise RuntimeError(
                f"Group key for {group_mid} was wrapped for our key "
                f"{receiver_kid}, which is not stored locally."
            )
        priv        = base64.b64decode(self_key["privKey"])
        creator_pub = self._peer_public_key(creator, creator_kid)

        shared  = _curve25519_shared(priv, creator_pub)
        # Note: no salt here, unlike message keys.
        aes_key = _sha256(shared, b"Key")
        aes_iv  = _xor_half(_sha256(shared, b"IV"))
        plain   = AES.new(aes_key, AES.MODE_CBC, aes_iv).decrypt(encrypted)
        try:
            plain = unpad(plain, 16)
        except ValueError:
            pass          # the wrapped key is exactly one block on some groups

        data = {"keyId": group_key_id,
                "privKey": base64.b64encode(plain).decode()}
        self._e2ee_group_cache[group_mid] = data
        try:
            _write_file(path, json.dumps(data))
        except OSError:
            pass
        return data

    def decrypt_message(self, msg: Message) -> Optional[str]:
        """Return the plaintext of a letter-sealed message."""
        if not msg.chunks:
            return msg.text
        return self._e2ee_payload(msg).get("text")

    def _e2ee_payload(self, msg: Message) -> Dict[str, Any]:
        """Decrypt a sealed message to its JSON payload.

        Text messages carry {"text": ...}; media messages carry
        {"keyMaterial": ...} used to decrypt the uploaded object.

        Works for messages in both directions.  Which chunk key id is *ours*
        depends on who sent it: for an incoming message we are the receiver,
        but for one we sent ourselves our key is the sender key id.
        """
        chunks = [_as_bytes(c) for c in msg.chunks]
        if len(chunks) < 5:
            raise RuntimeError(f"Unexpected chunk count {len(chunks)}")
        salt, enc, nonce = chunks[0], chunks[1], chunks[2]
        sender_kid   = int.from_bytes(chunks[3], "big")
        receiver_kid = int.from_bytes(chunks[4], "big")

        if (msg.to or "").startswith(("c", "r")):
            # Group: ECDH is between the group's shared private key and the
            # *sender's* public key — not a pairwise exchange with us.
            group = self._group_shared_key(msg.to, receiver_kid)
            priv  = base64.b64decode(group["privKey"])
            if msg.sender == getattr(self, "mid", None):
                own = self._self_key_by_id(sender_kid) or self._load_e2ee_key()
                if not own:
                    raise RuntimeError(
                        f"No local E2EE key with id {sender_kid}")
                peer_pub = base64.b64decode(own["pubKey"])
            else:
                peer_pub = self._peer_public_key(msg.sender, sender_kid)
        else:
            if msg.sender == getattr(self, "mid", None):
                self_kid, peer_kid, peer_mid = sender_kid, receiver_kid, msg.to
            else:
                self_kid, peer_kid, peer_mid = receiver_kid, sender_kid, msg.sender

            self_key = self._self_key_by_id(self_kid)
            if not self_key:
                raise RuntimeError(f"No local E2EE private key with id {self_kid}")
            priv     = base64.b64decode(self_key["privKey"])
            peer_pub = self._peer_public_key(peer_mid, peer_kid)

        meta = msg.content_metadata or {}
        spec = int(meta.get("e2eeVersion", 2))
        shared = _curve25519_shared(priv, peer_pub)
        # AAD always uses the message's own to/from and the on-wire key ids,
        # regardless of which side is decrypting.
        aad = ((msg.to or "").encode() + (msg.sender or "").encode()
               + _i32be(sender_kid) + _i32be(receiver_kid)
               + _i32be(spec) + _i32be(msg.content_type))

        if spec == 2:
            plain = AESGCM(_sha256(shared, salt, b"Key")).decrypt(nonce, enc, aad)
        else:
            aes_key = _sha256(shared, salt, b"Key")
            aes_iv  = _xor_half(_sha256(shared, salt, b"IV"))
            plain = unpad(AES.new(aes_key, AES.MODE_CBC, aes_iv).decrypt(enc), 16)
        return json.loads(plain)

    def _decrypted(self, msg: Message) -> Message:
        """Best-effort decrypt; a failure leaves the message untouched."""
        if not msg.chunks:
            return msg
        try:
            return msg._replace(text=self.decrypt_message(msg))
        except Exception as e:
            print(f"[LINE] could not decrypt {msg.id}: {type(e).__name__}: {e}")
            return msg

    def get_chat_summaries(self, include_peers: bool = True,
                           include_empty: bool = False,
                           max_workers: int = 8) -> List[ChatSummary]:
        """Reproduce the official client's "Chats" tab: each chat with its name
        and last message, newest first.

        LINE has no server call that returns this directly — the real client
        keeps a local message box synced from fetchOps.  Here it is assembled
        from one getRecentMessagesV2(count=1) per chat, issued concurrently.

        include_empty=False (the default) drops chats you have never exchanged
        a message in.  That matters when include_peers is on, since contacts
        cover every friend, not just the ones you have talked to.
        """
        from concurrent.futures import ThreadPoolExecutor

        # Names come from two different places depending on the chat kind.
        names: Dict[str, str] = {}
        types: Dict[str, Optional[int]] = {}

        for chat in self.get_chats():
            names[chat.chat_mid] = chat.chat_name or "(no name)"
            types[chat.chat_mid] = chat.chat_type

        contacts: Dict[str, str] = {}
        if include_peers:
            for c in self.get_contacts(self.get_all_contact_ids()):
                label = c.display_name_override or c.display_name or "(no name)"
                contacts[c.mid] = label
                names.setdefault(c.mid, label)
                types.setdefault(c.mid, ChatType.PEER)

        def last_message(mid: str):
            try:
                msgs = self.get_recent_messages(mid, count=1)
            except LineServiceError as e:
                print(f"[LINE] skipping {mid}: {e}")
                return mid, None
            return mid, (msgs[-1] if msgs else None)

        mids = list(names)
        with ThreadPoolExecutor(max_workers=max_workers) as pool:
            latest = dict(pool.map(last_message, mids))

        # Group senders are not necessarily in your contact list — someone you
        # never added, or who has since left.  Resolve those in one extra batch
        # so the preview shows a name instead of a raw MID.
        unknown = {m.sender for m in latest.values()
                   if m is not None and m.sender
                   and m.sender not in contacts
                   and m.sender != getattr(self, "mid", None)}
        if unknown:
            try:
                for c in self.get_contacts(sorted(unknown)):
                    contacts[c.mid] = (c.display_name_override
                                       or c.display_name or c.mid)
            except LineServiceError as e:
                print(f"[LINE] could not resolve {len(unknown)} sender(s): {e}")

        summaries = []
        for mid in mids:
            msg = latest.get(mid)
            if msg is None and not include_empty:
                continue
            sender = None
            if msg is not None and msg.sender:
                sender = ("You" if msg.sender == getattr(self, "mid", None)
                          else contacts.get(msg.sender, msg.sender))
            summaries.append(ChatSummary(
                chat_mid=mid,
                chat_type=types.get(mid),
                name=names.get(mid, mid),
                last_message=msg,
                sender_name=sender,
            ))

        summaries.sort(key=lambda s: s.timestamp, reverse=True)
        return summaries

    def get_recent_messages(self, chat_mid: str, count: int = 20) -> List[Message]:
        """Return recent messages in a chat (newest last)."""
        params = [
            [11, 2, chat_mid],
            [8,  3, count],
        ]
        data = pack_compact("getRecentMessagesV2", params)
        res = self._post_talk(_EP_TALK, data)
        # This method's Thrift result is a bare list, so the decoder keys it at 0.
        raw = res.get(0) or []
        return [self._decrypted(Message.from_dict(m))
                for m in raw if isinstance(m, dict)]

    # ------------------------------------------------------------------
    # Long-polling
    # ------------------------------------------------------------------

    def get_last_op_revision(self) -> int:
        """The account's current op revision."""
        data = pack_compact("getLastOpRevision", [])
        res = self._post_talk(_EP_TALK, data)
        return int(res.get(0) or 0)

    def _fetch_ops(self, count: int = 100) -> List[Operation]:
        params = [
            [10, 2, self._revision],
            [8,  3, count],
            [10, 4, self._global_rev],
            [10, 5, self._individual_rev],
        ]
        data = pack_compact("fetchOps", params)
        raw_ops = self._post_poll(_EP_POLL, data, timeout=110)
        ops: List[Operation] = []
        for raw in (raw_ops if isinstance(raw_ops, list) else []):
            op_type = raw.get(3) if isinstance(raw, dict) else None
            rev = raw.get(1) if isinstance(raw, dict) else None
            if rev is not None and op_type != -1:
                self._revision = max(self._revision, int(rev))
            # op_type 0 carries revision updates embedded in param1/param2
            if op_type == 0:
                p1 = raw.get(10) or ""
                p2 = raw.get(11) or ""
                if "\x1e" in str(p1):
                    self._individual_rev = int(str(p1).split("\x1e")[0])
                if "\x1e" in str(p2):
                    self._global_rev = int(str(p2).split("\x1e")[0])
            ops.append(Operation.from_dict(raw) if isinstance(raw, dict) else raw)
        return ops

    # ------------------------------------------------------------------
    # Event handler registration
    # ------------------------------------------------------------------

    def on_message(self, func: Callable) -> Callable:
        """Decorator: register a handler called with each received message op."""
        self._message_handlers.append(func)
        return func

    def on_op(self, func: Callable) -> Callable:
        """Decorator: register a handler called for every operation."""
        self._op_handlers.append(func)
        return func

    # ------------------------------------------------------------------
    # Listen loop
    # ------------------------------------------------------------------

    def listen(self, threaded: bool = True) -> None:
        """
        Start long-polling for events (blocks the calling thread).

        For each received message op, calls handlers registered via
        @line.on_message(msg, op). For every op, calls @line.on_op handlers.

        Set threaded=False to run handlers synchronously.
        """
        # Start from the current revision, otherwise the server replays the
        # account's whole operation backlog before reaching anything live.
        if not self._revision:
            try:
                self._revision = self.get_last_op_revision()
                print(f"[LINE] Starting from revision {self._revision}")
            except LineServiceError as e:
                print(f"[LINE] could not get last revision ({e}); starting at 0")

        print("[LINE] Listening for events… (Ctrl-C to stop)")
        while True:
            try:
                ops = self._fetch_ops()
                for op in ops:
                    if op.op_type in (-1, 0):
                        continue
                    if threaded:
                        t = threading.Thread(target=self._dispatch, args=(op,), daemon=True)
                        t.start()
                    else:
                        self._dispatch(op)
            except LineServiceError as e:
                print(f"[LINE] API error during poll: {e}")
                time.sleep(5)
            except KeyboardInterrupt:
                print("\n[LINE] Stopped.")
                break
            except Exception as e:
                print(f"[LINE] Poll error: {e}")
                time.sleep(5)

    def _dispatch(self, op: Operation) -> None:
        for h in self._op_handlers:
            try:
                h(op)
            except Exception as e:
                print(f"[LINE] on_op handler error: {e}")
        if op.op_type == OpType.RECEIVE_MESSAGE and op.message is not None:
            message = self._decrypted(op.message)
            for h in self._message_handlers:
                try:
                    h(message, op)
                except Exception as e:
                    print(f"[LINE] on_message handler error: {e}")
