# -*- coding: utf-8 -*-
"""
Minimal Apache Thrift encode/decode for LINE's TBinary and TCompact protocols.

Param list format (same as CHRLINE):
    [[type, field_id, value], ...]

Type codes:
    2  = bool
    3  = byte
    4  = double
    8  = i32
    10 = i64
    11 = string / bytes
    12 = struct  -> value is another param list
    13 = map     -> value is [ktype, vtype, {k: v}]
    14 = set     -> value is [etype, [items]]
    15 = list    -> value is [etype, [items]]
"""
import struct
from typing import Any, Dict, List, Optional, Tuple, Union

# ---------------------------------------------------------------------------
# Low-level helpers
# ---------------------------------------------------------------------------

CTYPES = {
    2: 0x01,   # bool (true in container)
    3: 0x03,   # byte
    4: 0x07,   # double
    6: 0x04,   # i16
    8: 0x05,   # i32
    10: 0x06,  # i64
    11: 0x08,  # binary / string
    12: 0x0C,  # struct
    13: 0x0B,  # map
    14: 0x0A,  # set
    15: 0x09,  # list
}

TTYPES = {v: k for k, v in CTYPES.items()}
TTYPES[0x02] = 2   # bool-false also decodes as bool


def _zigzag_enc(n: int, bits: int) -> int:
    return (n << 1) ^ (n >> (bits - 1))


def _zigzag_dec(n: int) -> int:
    return (n >> 1) ^ -(n & 1)


def _write_varint(n: int) -> bytes:
    out = bytearray()
    while True:
        if n & ~0x7F == 0:
            out.append(n)
            break
        out.append((n & 0xFF) | 0x80)
        n >>= 7
    return bytes(out)


def _read_varint(data: bytes, pos: int) -> Tuple[int, int]:
    result, shift = 0, 0
    while True:
        b = data[pos]; pos += 1
        result |= (b & 0x7F) << shift
        if (b & 0x80) == 0:
            return result, pos
        shift += 7


def _write_i32_be(n: int) -> bytes:
    return struct.pack("!i", n)


def _write_i64_be(n: int) -> bytes:
    return struct.pack("!q", n)


def _write_string_bin(s: Union[str, bytes]) -> bytes:
    if isinstance(s, str):
        s = s.encode()
    return _write_i32_be(len(s)) + s


def _write_string_compact(s: Union[str, bytes]) -> bytes:
    if isinstance(s, str):
        s = s.encode()
    return _write_varint(len(s)) + s


# ---------------------------------------------------------------------------
# TBinary encoder
# ---------------------------------------------------------------------------

def _encode_bin_data(ttype: int, value: Any) -> bytes:
    if ttype == 2:
        return b'\x01' if value else b'\x00'
    if ttype == 3:
        return struct.pack("!b", value)
    if ttype == 4:
        return struct.pack("!d", value)
    if ttype == 8:
        return _write_i32_be(value)
    if ttype == 10:
        return _write_i64_be(value)
    if ttype == 11:
        return _write_string_bin(value)
    if ttype == 12:
        return _encode_bin_fields(value)
    if ttype == 13:
        ktype, vtype, mapping = value
        out = struct.pack("!bb", ktype, vtype) + _write_i32_be(len(mapping))
        for k, v in mapping.items():
            out += _encode_bin_data(ktype, k)
            out += _encode_bin_data(vtype, v)
        return out
    if ttype in (14, 15):
        etype, items = value
        out = struct.pack("!b", etype) + _write_i32_be(len(items))
        for item in items:
            out += _encode_bin_data(etype, item)
        return out
    raise ValueError(f"Unsupported TBinary type: {ttype}")


def _encode_bin_fields(params: list) -> bytes:
    out = b''
    for param in params:
        ttype, fid, value = param
        if value is None:
            continue
        out += struct.pack("!bh", ttype, fid)
        out += _encode_bin_data(ttype, value)
    return out + b'\x00'


def pack_binary(method_name: str, params: list, seq_id: int = 0) -> bytes:
    """Encode a TBinary Thrift CALL message."""
    name_bytes = method_name.encode()
    header = (
        b'\x80\x01\x00\x01'
        + _write_i32_be(len(name_bytes))
        + name_bytes
        + _write_i32_be(seq_id)
    )
    return header + _encode_bin_fields(params)


# ---------------------------------------------------------------------------
# TCompact encoder
# ---------------------------------------------------------------------------

def _encode_compact_data(ttype: int, value: Any, last_fid_ref: list) -> bytes:
    if ttype == 2:
        return b'\x01' if value else b'\x02'
    if ttype == 3:
        return struct.pack("!b", value)
    if ttype == 4:
        return struct.pack("<d", value)
    if ttype == 8:
        return _write_varint(_zigzag_enc(value, 32))
    if ttype == 10:
        return _write_varint(_zigzag_enc(value, 64))
    if ttype == 11:
        return _write_string_compact(value)
    if ttype == 12:
        return _encode_compact_fields(value)
    if ttype == 13:
        ktype, vtype, mapping = value
        if len(mapping) == 0:
            return b'\x00'
        out = _write_varint(len(mapping))
        out += bytes([(CTYPES[ktype] << 4) | CTYPES[vtype]])
        for k, v in mapping.items():
            out += _encode_compact_data(ktype, k, [0])
            out += _encode_compact_data(vtype, v, [0])
        return out
    if ttype in (14, 15):
        etype, items = value
        n = len(items)
        if n <= 14:
            out = bytes([(n << 4) | CTYPES[etype]])
        else:
            out = bytes([0xF0 | CTYPES[etype]]) + _write_varint(n)
        for item in items:
            out += _encode_compact_data(etype, item, [0])
        return out
    raise ValueError(f"Unsupported TCompact type: {ttype}")


def _encode_compact_fields(params: list) -> bytes:
    out = b''
    last_fid = 0
    for param in params:
        ttype, fid, value = param
        if value is None:
            continue
        if ttype == 13 and value[2] is None:
            continue
        if ttype in (14, 15) and value[1] is None:
            continue

        ctype = CTYPES[ttype]
        if ttype == 2:
            ctype = 0x01 if value else 0x02

        delta = fid - last_fid
        if 0 < delta <= 15:
            out += bytes([(delta << 4) | ctype])
        else:
            out += bytes([ctype]) + _write_varint(_zigzag_enc(fid, 16))
        last_fid = fid

        if ttype != 2:  # bool is encoded in the type byte itself
            out += _encode_compact_data(ttype, value, [last_fid])
    return out + b'\x00'


def pack_compact(method_name: str, params: list, seq_id: int = 0) -> bytes:
    """Encode a TCompact Thrift CALL message."""
    name_bytes = method_name.encode()
    # protocol_id=0x82, type=CALL(1) ver=1 → 0x21, seq_id, name
    header = (
        b'\x82\x21'
        + _write_varint(seq_id)
        + _write_string_compact(name_bytes)
    )
    return header + _encode_compact_fields(params)


# ---------------------------------------------------------------------------
# TBinary decoder
# ---------------------------------------------------------------------------

class _BinReader:
    def __init__(self, data: bytes):
        self._d = data
        self._p = 0

    def read(self, n: int) -> bytes:
        chunk = self._d[self._p:self._p + n]
        self._p += n
        return chunk

    def read_i8(self) -> int:
        return struct.unpack("!b", self.read(1))[0]

    def read_i16(self) -> int:
        return struct.unpack("!h", self.read(2))[0]

    def read_i32(self) -> int:
        return struct.unpack("!i", self.read(4))[0]

    def read_i64(self) -> int:
        return struct.unpack("!q", self.read(8))[0]

    def read_double(self) -> float:
        return struct.unpack("!d", self.read(8))[0]

    def read_string(self) -> Any:
        n = self.read_i32()
        raw = self.read(n)
        try:
            return raw.decode("utf-8")
        except UnicodeDecodeError:
            return raw

    def _read_value(self, ttype: int) -> Any:
        if ttype == 2:
            return self.read_i8() != 0
        if ttype == 3:
            return self.read_i8()
        if ttype == 4:
            return self.read_double()
        if ttype == 6:
            return self.read_i16()
        if ttype == 8:
            return self.read_i32()
        if ttype == 10:
            return self.read_i64()
        if ttype == 11:
            return self.read_string()
        if ttype == 12:
            return self._read_struct()
        if ttype == 13:
            ktype = self.read_i8()
            vtype = self.read_i8()
            n = self.read_i32()
            return {self._read_value(ktype): self._read_value(vtype) for _ in range(n)}
        if ttype in (14, 15):
            etype = self.read_i8()
            n = self.read_i32()
            return [self._read_value(etype) for _ in range(n)]
        raise ValueError(f"Unknown TBinary type: {ttype}")

    def _read_struct(self) -> Dict[int, Any]:
        result = {}
        while True:
            ttype = self.read_i8()
            if ttype == 0:
                break
            fid = self.read_i16()
            result[fid] = self._read_value(ttype)
        return result

    def read_message(self) -> Tuple[str, int, Dict[int, Any]]:
        sz = self.read_i32()
        if sz >= 0:
            raise ValueError(f"Old-style TBinary message not supported: sz={sz}")
        version = sz & 0xFFFF0000
        if version != 0x80010000:
            raise ValueError(f"Bad TBinary version: {version:#x}")
        mtype = sz & 0x000000FF
        name = self.read_string()
        seq_id = self.read_i32()
        return name, mtype, seq_id

    def unpack(self) -> Dict[int, Any]:
        name, mtype, seq_id = self.read_message()
        ttype = self.read_i8()
        if ttype == 0:
            return {}
        fid = self.read_i16()
        value = self._read_value(ttype)
        if fid == 0:
            return value if isinstance(value, dict) else {0: value}
        # fid == 1 means exception
        raise _make_line_error(value)


# ---------------------------------------------------------------------------
# TCompact decoder
# ---------------------------------------------------------------------------

class _CompactReader:
    def __init__(self, data: bytes):
        self._d = data
        self._p = 0
        self._bool_val = None

    def read(self, n: int) -> bytes:
        chunk = self._d[self._p:self._p + n]
        self._p += n
        return chunk

    def read_ubyte(self) -> int:
        b = self._d[self._p]; self._p += 1
        return b

    def read_varint(self) -> int:
        v, new_p = _read_varint(self._d, self._p)
        self._p = new_p
        return v

    def read_i16(self) -> int:
        return _zigzag_dec(self.read_varint())

    def read_i32(self) -> int:
        return _zigzag_dec(self.read_varint())

    def read_i64(self) -> int:
        return _zigzag_dec(self.read_varint())

    def read_i8(self) -> int:
        return struct.unpack("!b", self.read(1))[0]

    def read_double(self) -> float:
        return struct.unpack("<d", self.read(8))[0]

    def read_string(self) -> Any:
        n = self.read_varint()
        raw = self.read(n)
        try:
            return raw.decode("utf-8")
        except UnicodeDecodeError:
            return raw

    def _read_value(self, ctype: int) -> Any:
        if ctype == 0x01:
            return True
        if ctype == 0x02:
            return False
        ttype = TTYPES.get(ctype)
        if ttype is None:
            raise ValueError(f"Unknown compact ctype: {ctype:#x}")
        if ttype == 2:
            return self._bool_val
        if ttype == 3:
            return self.read_i8()
        if ttype == 4:
            return self.read_double()
        if ttype == 8:
            return self.read_i32()
        if ttype == 10:
            return self.read_i64()
        if ttype == 11:
            return self.read_string()
        if ttype == 12:
            return self._read_struct()
        if ttype == 13:
            return self._read_map()
        if ttype in (14, 15):
            return self._read_list()
        raise ValueError(f"Unsupported compact ttype: {ttype}")

    def _read_struct(self) -> Dict[int, Any]:
        result = {}
        last_fid = 0
        while True:
            b = self.read_ubyte()
            if b == 0:
                break
            ctype = b & 0x0F
            delta = b >> 4
            if delta == 0:
                fid = self.read_i16()
            else:
                fid = last_fid + delta
            last_fid = fid
            if ctype in (0x01, 0x02):
                self._bool_val = (ctype == 0x01)
            result[fid] = self._read_value(ctype)
        return result

    def _read_map(self) -> Dict[Any, Any]:
        n = self.read_varint()
        if n == 0:
            return {}
        types = self.read_ubyte()
        ktype = types >> 4
        vtype = types & 0x0F
        return {self._read_value(ktype): self._read_value(vtype) for _ in range(n)}

    def _read_list(self) -> List[Any]:
        b = self.read_ubyte()
        n = b >> 4
        etype = b & 0x0F
        if n == 15:
            n = self.read_varint()
        return [self._read_value(etype) for _ in range(n)]

    def read_message(self) -> Tuple[str, int, int]:
        proto_id = self.read_ubyte()
        if proto_id != 0x82:
            raise ValueError(f"Bad compact protocol id: {proto_id:#x}")
        ver_type = self.read_ubyte()
        mtype = (ver_type >> 5) & 0x07
        version = ver_type & 0x1F
        if version != 1:
            raise ValueError(f"Bad compact version: {version}")
        seq_id = self.read_varint()
        name = self.read_string()
        return name, mtype, seq_id

    def unpack(self) -> Dict[int, Any]:
        name, mtype, seq_id = self.read_message()
        b = self.read_ubyte()
        if b == 0:
            return {}
        ctype = b & 0x0F
        delta = b >> 4
        fid = delta if delta != 0 else self.read_i16()
        value = self._read_value(ctype)
        if fid == 0:
            return value if isinstance(value, dict) else {0: value}
        raise _make_line_error(value)


# ---------------------------------------------------------------------------
# Error handling
# ---------------------------------------------------------------------------

class LineServiceError(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code
        self.message = message

    def __str__(self):
        return f"[code={self.code}] {self.message}"

    def __repr__(self):
        return f"LineServiceError(code={self.code}, message={self.message!r})"


def _make_line_error(fields: Dict) -> LineServiceError:
    code = fields.get(1) or fields.get("code") or 0
    msg = fields.get(2) or fields.get("message") or str(fields)
    return LineServiceError(int(code), str(msg))


# ---------------------------------------------------------------------------
# Public decode helpers
# ---------------------------------------------------------------------------

def unpack_binary(data: bytes) -> Dict[int, Any]:
    """Decode a TBinary response. Returns field-id dict or raises LineServiceError."""
    return _BinReader(data).unpack()


def unpack_compact(data: bytes) -> Dict[int, Any]:
    """Decode a TCompact response. Returns field-id dict or raises LineServiceError."""
    return _CompactReader(data).unpack()


def pack_binary_struct(params: list) -> bytes:
    """Encode a bare TBinary struct — no CALL message envelope.

    Used for payloads nested inside other encodings, such as the X-Talk-Meta
    header that authorises a media download.
    """
    return _encode_bin_fields(params)


def unpack_compact_struct(data: bytes) -> Dict[int, Any]:
    """Decode a bare TCompact struct — no message envelope, no exception check.

    Used for payloads that are Thrift-encoded but not RPC responses, such as
    the E2EE key chain returned by the primary device.
    """
    return _CompactReader(data)._read_struct()
