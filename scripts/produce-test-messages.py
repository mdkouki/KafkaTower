#!/usr/bin/env python3
"""Produce N random Avro or Protobuf test messages to a Kafka topic. Python 3 standard library only.

Payloads are raw (no Confluent framing), matching what the Topics > Content tab decodes after you
upload a schema for the topic. The schema to upload is written to --schema-out (default: scripts/schemas).

Usage:  ./scripts/produce-test-messages.py avro     -n 100 -t orders.commands.confirmed
        ./scripts/produce-test-messages.py protobuf -n 50  -t fulfillment.updates

Connection defaults match compose.yml (EXTERNAL listener, SASL/PLAIN as 'admin'); override with
KAFKA_BOOTSTRAP_SERVERS, KAFKA_USERNAME, KAFKA_PASSWORD. Set KAFKA_SASL=0 for a plaintext broker.
Protobuf message name to enter in the UI: test.Order

Kafka client note: this speaks the wire protocol directly (SaslHandshake v1, SaslAuthenticate v0,
Metadata v4, Produce v3 with a v2 record batch) to avoid any client library.
"""
import argparse
import json
import os
import random
import socket
import struct
import sys
import time
import uuid
from pathlib import Path

CUSTOMERS = ["acme", "globex", "initech", "umbrella", "hooli"]
STATUSES = ["NEW", "PAID", "SHIPPED"]
TAGS = ["priority", "gift", "fragile", "express", "bulk"]


def random_order():
    return {
        "id": str(uuid.uuid4()),
        "customer": random.choice(CUSTOMERS),
        "amount": round(random.uniform(5, 500), 2),
        "quantity": random.randint(1, 20),
        "status": random.choice(STATUSES),
        "tags": random.sample(TAGS, random.randint(0, 3)),
        "created_at": int(time.time() * 1000),
    }


# ── varints ────────────────────────────────────────────────────────────────────

def varint(n):
    """Unsigned LEB128 (protobuf varint)."""
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def zigzag(n):
    """Avro int/long and Kafka record varints."""
    return varint((n << 1) ^ (n >> 63))


# ── Avro (binary, schemaless) ──────────────────────────────────────────────────

AVRO_SCHEMA = {
    "type": "record",
    "name": "Order",
    "namespace": "test",
    "fields": [
        {"name": "id", "type": "string"},
        {"name": "customer", "type": "string"},
        {"name": "amount", "type": "double"},
        {"name": "quantity", "type": "int"},
        {"name": "status", "type": {"type": "enum", "name": "Status", "symbols": STATUSES}},
        {"name": "tags", "type": {"type": "array", "items": "string"}},
        {"name": "created_at", "type": "long"},
    ],
}


def avro_string(s):
    b = s.encode()
    return zigzag(len(b)) + b


def avro_encode(o):
    out = avro_string(o["id"]) + avro_string(o["customer"])
    out += struct.pack("<d", o["amount"]) + zigzag(o["quantity"]) + zigzag(STATUSES.index(o["status"]))
    if o["tags"]:
        out += zigzag(len(o["tags"])) + b"".join(avro_string(t) for t in o["tags"])
    return out + zigzag(0) + zigzag(o["created_at"])


# ── Protobuf (wire format + hand-built FileDescriptorSet) ──────────────────────

def pb_len(field, payload):
    return varint(field << 3 | 2) + varint(len(payload)) + payload


def pb_str(field, s):
    return pb_len(field, s.encode())


def pb_int(field, n):
    return varint(field << 3 | 0) + varint(n)


def protobuf_encode(o):
    return (pb_str(1, o["id"]) + pb_str(2, o["customer"])
            + varint(3 << 3 | 1) + struct.pack("<d", o["amount"])
            + pb_int(4, o["quantity"]) + pb_int(5, STATUSES.index(o["status"]))
            + b"".join(pb_str(6, t) for t in o["tags"])
            + pb_int(7, o["created_at"]))


def protobuf_descriptor_set():
    """Binary FileDescriptorSet for `package test; message Order {...}` (proto3)."""
    # (name, number, label 1=optional/3=repeated, type: 1=double 3=int64 5=int32 9=string 14=enum)
    fields = [("id", 1, 1, 9), ("customer", 2, 1, 9), ("amount", 3, 1, 1), ("quantity", 4, 1, 5),
              ("status", 5, 1, 14), ("tags", 6, 3, 9), ("created_at", 7, 1, 3)]
    msg = pb_str(1, "Order")
    for name, num, label, typ in fields:
        f = pb_str(1, name) + pb_int(3, num) + pb_int(4, label) + pb_int(5, typ)
        if typ == 14:
            f += pb_str(6, ".test.Status")
        msg += pb_len(2, f)
    enum = pb_str(1, "Status") + b"".join(pb_len(2, pb_str(1, n) + pb_int(2, i)) for i, n in enumerate(STATUSES))
    file = pb_str(1, "order.proto") + pb_str(2, "test") + pb_len(4, msg) + pb_len(5, enum) + pb_str(12, "proto3")
    return pb_len(1, file)


# ── Kafka wire protocol ────────────────────────────────────────────────────────

def _crc32c_table():
    table = []
    for i in range(256):
        c = i
        for _ in range(8):
            c = (c >> 1) ^ 0x82F63B78 if c & 1 else c >> 1
        table.append(c)
    return table


_CRC = _crc32c_table()


def crc32c(data):
    c = 0xFFFFFFFF
    for b in data:
        c = _CRC[(c ^ b) & 0xFF] ^ (c >> 8)
    return c ^ 0xFFFFFFFF


def k_str(s):
    b = s.encode()
    return struct.pack(">h", len(b)) + b


def record_batch(records):
    """records: list of (key, value) bytes. One uncompressed v2 batch."""
    now = int(time.time() * 1000)
    body = b""
    for i, (key, value) in enumerate(records):
        r = (b"\x00" + zigzag(0) + zigzag(i) + zigzag(len(key)) + key
             + zigzag(len(value)) + value + zigzag(0))
        body += zigzag(len(r)) + r
    after_crc = struct.pack(">hiqqqhii", 0, len(records) - 1, now, now, -1, -1, -1, len(records)) + body
    after_len = struct.pack(">ibI", -1, 2, crc32c(after_crc)) + after_crc
    return struct.pack(">qi", 0, len(after_len)) + after_len


class Broker:
    def __init__(self, host, port, user, password):
        self.sock = socket.create_connection((host, port), timeout=30)
        self.corr = 0
        if user is not None:
            self._call(17, 1, k_str("PLAIN"), "SaslHandshake", check_leading_error=True)
            token = f"\0{user}\0{password}".encode()
            self._call(36, 0, struct.pack(">i", len(token)) + token, "SaslAuthenticate", check_leading_error=True)

    def _recv(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("broker closed the connection (wrong credentials or listener?)")
            buf += chunk
        return buf

    def _call(self, api_key, version, body, name, check_leading_error=False):
        self.corr += 1
        header = struct.pack(">hhi", api_key, version, self.corr) + k_str("produce-test-messages")
        self.sock.sendall(struct.pack(">i", len(header) + len(body)) + header + body)
        resp = self._recv(struct.unpack(">i", self._recv(4))[0])
        if struct.unpack(">i", resp[:4])[0] != self.corr:
            raise RuntimeError(f"{name}: correlation id mismatch")
        resp = resp[4:]
        if check_leading_error and (err := struct.unpack(">h", resp[:2])[0]):
            raise RuntimeError(f"{name} failed: Kafka error code {err}")
        return resp

    def partitions(self, topic):
        """Returns (brokers {id: (host, port)}, {partition: leader id}) from Metadata v4."""
        resp = self._call(3, 4, struct.pack(">i", 1) + k_str(topic) + b"\x00", "Metadata")
        pos = 4  # throttle_time_ms

        def i32():
            nonlocal pos
            pos += 4
            return struct.unpack(">i", resp[pos - 4:pos])[0]

        def i16():
            nonlocal pos
            pos += 2
            return struct.unpack(">h", resp[pos - 2:pos])[0]

        def string():
            nonlocal pos
            n = i16()
            if n < 0:
                return None
            pos += n
            return resp[pos - n:pos].decode()

        brokers = {}
        for _ in range(i32()):
            node, host, port = i32(), string(), i32()
            string()  # rack
            brokers[node] = (host, port)
        string()  # cluster id
        i32()  # controller id
        leaders = {}
        for _ in range(i32()):
            topic_err = i16()
            string()
            pos += 1  # is_internal
            for _ in range(i32()):
                part_err, part, leader = i16(), i32(), i32()
                for _ in range(2):  # replicas, isr
                    skip = 4 * i32()
                    pos += skip
                if not part_err:
                    leaders[part] = leader
            if topic_err:
                reason = {3: "does not exist", 29: "not authorized"}.get(topic_err, "?")
                raise RuntimeError(f"topic '{topic}': Kafka error code {topic_err} ({reason})")
        return brokers, leaders

    def produce(self, topic, partition, batch):
        # null transactional id, acks=1, timeout 30s, one topic, one partition
        body = (struct.pack(">hhi", -1, 1, 30000) + struct.pack(">i", 1) + k_str(topic)
                + struct.pack(">ii", 1, partition) + struct.pack(">i", len(batch)) + batch)
        resp = self._call(0, 3, body, "Produce")
        # response: [topics(1)] name, [partitions(1)] index, error_code
        name_len = struct.unpack(">h", resp[4:6])[0]
        err_at = 6 + name_len + 4 + 4
        err = struct.unpack(">h", resp[err_at:err_at + 2])[0]
        if err:
            raise RuntimeError(f"Produce to {topic}-{partition} failed: Kafka error code {err}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("format", choices=["avro", "protobuf"])
    p.add_argument("-n", "--count", type=int, required=True, help="number of messages to produce")
    p.add_argument("-t", "--topic", required=True)
    p.add_argument("--schema-out", default=str(Path(__file__).resolve().parent / "schemas"),
                   help="directory for the schema to upload in the UI (default: scripts/schemas)")
    args = p.parse_args()
    if args.count < 1:
        p.error("--count must be >= 1")

    if args.format == "avro":
        encode, schema_bytes, schema_file = avro_encode, json.dumps(AVRO_SCHEMA, indent=2).encode(), "avro-order.avsc"
    else:
        encode, schema_bytes, schema_file = protobuf_encode, protobuf_descriptor_set(), "order.desc"
    out = Path(args.schema_out)
    out.mkdir(parents=True, exist_ok=True)
    (out / schema_file).write_bytes(schema_bytes)

    host, _, port = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092").split(",")[0].partition(":")
    sasl = os.getenv("KAFKA_SASL", "1") != "0"
    user, password = (os.getenv("KAFKA_USERNAME", "admin"), os.getenv("KAFKA_PASSWORD", "admin-secret")) if sasl else (None, None)

    try:
        boot = Broker(host, int(port or 9092), user, password)
        brokers, leaders = boot.partitions(args.topic)
        if not leaders:
            raise RuntimeError(f"topic '{args.topic}' has no available partitions")
        conns = {}
        by_partition = {part: [] for part in leaders}
        parts = sorted(leaders)
        for i in range(args.count):
            order = random_order()
            by_partition[parts[i % len(parts)]].append((order["id"].encode(), encode(order)))
        for part, records in by_partition.items():
            for start in range(0, len(records), 500):
                leader = brokers[leaders[part]]
                if leader not in conns:
                    conns[leader] = Broker(*leader, user, password)
                conns[leader].produce(args.topic, part, record_batch(records[start:start + 500]))
    except (OSError, RuntimeError) as e:
        print(f"error: {e}", file=sys.stderr)
        sys.exit(1)

    print(f"produced {args.count} {args.format} messages to {args.topic}; schema: {out / schema_file}")


if __name__ == "__main__":
    main()
