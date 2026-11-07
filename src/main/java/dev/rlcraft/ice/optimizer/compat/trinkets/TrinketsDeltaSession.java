package dev.rlcraft.ice.optimizer.compat.trinkets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per-connection byte transport; decoding never suppresses an original message. */
public final class TrinketsDeltaSession {
    public static final int MAX_PAYLOAD = 2 * 1024 * 1024;
    private static final int MAGIC = 0x49434E31;
    private static final int MIN_SAVING = 16;
    private static final int MERGE_GAP = 8;

    private TrinketsDeltaSession() { }

    public static final class Key {
        public final int entityId;
        public final int handler;
        public final int slot;

        public Key(int entityId, int handler, int slot) {
            this.entityId = entityId; this.handler = handler; this.slot = slot;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return entityId == key.entityId && handler == key.handler && slot == key.slot;
        }

        @Override public int hashCode() { return (entityId * 31 + handler) * 31 + slot; }
    }

    public static final class Frame {
        public static final int HEADER_BYTES = 45;
        public final long epoch;
        public final long sequence;
        public final Key key;
        public final long baseRevision;
        public final boolean full;
        private final byte[] body;

        private Frame(long epoch, long sequence, Key key, long baseRevision, boolean full,
                      byte[] body) {
            this.epoch = epoch; this.sequence = sequence; this.key = key;
            this.baseRevision = baseRevision; this.full = full; this.body = body;
        }

        public int bodyLength() { return body.length; }

        public void write(ByteBuf output) {
            output.writeInt(MAGIC); output.writeBoolean(full);
            output.writeLong(epoch); output.writeLong(sequence);
            output.writeInt(key.entityId); output.writeInt(key.handler); output.writeInt(key.slot);
            output.writeLong(baseRevision);
            output.writeInt(body.length); output.writeBytes(body);
        }

        public static Frame read(ByteBuf input) {
            require(input.readableBytes() >= HEADER_BYTES, "truncated frame header");
            require(input.readInt() == MAGIC, "unknown frame format");
            int flag = input.readUnsignedByte(); require(flag <= 1, "invalid frame flag");
            long epoch = input.readLong(), sequence = input.readLong();
            Key key = new Key(input.readInt(), input.readInt(), input.readInt());
            long base = input.readLong(); int length = input.readInt();
            require(sequence > 0 && sequence < Long.MAX_VALUE, "invalid frame sequence");
            require(flag == 1 ? base == 0 : base > 0 && base < sequence, "invalid base revision");
            require(length >= 0 && length <= MAX_PAYLOAD && length <= input.readableBytes(), "invalid frame length");
            byte[] body = new byte[length]; input.readBytes(body);
            return new Frame(epoch, sequence, key, base, flag == 1, body);
        }
    }

    /** Calls for one connection must be ordered together with the actual send. */
    public static final class Sender {
        private final long epoch;
        private final Cache cache;
        private long sequence;

        public Sender(long epoch, int maxEntries, int maxBytes) {
            this.epoch = epoch; this.cache = new Cache(maxEntries, maxBytes);
        }

        public Frame encode(Key key, byte[] immutablePayload) {
            require(key != null && immutablePayload != null, "missing key or payload");
            require(immutablePayload.length <= MAX_PAYLOAD, "payload exceeds transport limit");
            require(sequence < Long.MAX_VALUE - 1, "sequence exhausted; rotate epoch");
            byte[] payload = immutablePayload.clone();
            Entry previous = cache.entries.get(key);
            byte[] difference = previous == null || !cache.canStore(payload.length)
                ? null : difference(previous.payload, payload);
            boolean full = difference == null;
            long revision = sequence + 1;
            Frame frame = new Frame(epoch, revision, key, full ? 0 : previous.revision,
                full, full ? payload : difference);
            cache.commit(key, payload, revision);
            sequence = revision;
            return frame;
        }
    }

    /** Uses exactly the sender's entry and byte budgets; do not clear independently. */
    public static final class Receiver {
        private final Cache cache;
        private boolean initialized;
        private long epoch;
        private long sequence;

        public Receiver(int maxEntries, int maxBytes) { cache = new Cache(maxEntries, maxBytes); }

        public byte[] decode(Frame frame) {
            require(frame != null, "missing frame");
            boolean reset = !initialized || frame.epoch != epoch;
            require(reset ? frame.full && frame.sequence == 1 : frame.sequence == sequence + 1,
                "out-of-order frame or invalid epoch reset");
            byte[] payload;
            if (frame.full) {
                require(frame.baseRevision == 0 && frame.body.length <= MAX_PAYLOAD, "invalid full frame");
                payload = frame.body;
            } else {
                Entry previous = cache.entries.get(frame.key);
                require(previous != null && previous.revision == frame.baseRevision, "missing or stale delta baseline");
                payload = restore(previous.payload, frame.body);
            }
            // No malformed frame may advance the stream or disturb the LRU.
            if (reset) cache.clear();
            cache.commit(frame.key, payload, frame.sequence);
            initialized = true; epoch = frame.epoch; sequence = frame.sequence;
            return payload.clone();
        }
    }

    private static final class Entry {
        final byte[] payload;
        final long revision;
        Entry(byte[] payload, long revision) { this.payload = payload; this.revision = revision; }
    }

    private static final class Cache {
        final int maxEntries;
        final int maxBytes;
        final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<Key, Entry>();
        int bytes;

        Cache(int maxEntries, int maxBytes) {
            require(maxEntries >= 0 && maxBytes >= 0, "negative cache budget");
            this.maxEntries = maxEntries; this.maxBytes = maxBytes;
        }

        boolean canStore(int size) { return maxEntries > 0 && size <= maxBytes; }

        void commit(Key key, byte[] payload, long revision) {
            Entry previous = entries.remove(key);
            if (previous != null) bytes -= previous.payload.length;
            if (!canStore(payload.length)) return;
            Iterator<Map.Entry<Key, Entry>> oldest = entries.entrySet().iterator();
            while (entries.size() >= maxEntries || (long) bytes + payload.length > maxBytes) {
                Map.Entry<Key, Entry> victim = oldest.next();
                bytes -= victim.getValue().payload.length; oldest.remove();
            }
            entries.put(key, new Entry(payload, revision)); bytes += payload.length;
        }

        void clear() { entries.clear(); bytes = 0; }
    }

    private static byte[] difference(byte[] base, byte[] payload) {
        if (payload.length <= MIN_SAVING) return null;
        ByteBuf output = Unpooled.buffer(Math.min(payload.length, 256));
        try {
            output.writeInt(payload.length);
            if (base.length != payload.length) {
                int prefix = 0, limit = Math.min(base.length, payload.length);
                while (prefix < limit && base[prefix] == payload[prefix]) prefix++;
                int suffix = 0;
                while (suffix < limit - prefix && base[base.length - suffix - 1] == payload[payload.length - suffix - 1]) suffix++;
                output.writeByte(1); output.writeInt(prefix); output.writeInt(suffix);
                output.writeBytes(payload, prefix, payload.length - prefix - suffix);
            } else {
                output.writeByte(0); int countPosition = output.writerIndex(); output.writeInt(0);
                int cursor = 0, spans = 0;
                while (cursor < payload.length) {
                    while (cursor < payload.length && base[cursor] == payload[cursor]) cursor++;
                    if (cursor == payload.length) break;
                    int start = cursor, end = cursor + 1; cursor++;
                    while (cursor < payload.length) {
                        if (base[cursor] != payload[cursor]) end = cursor + 1;
                        else if (cursor - end >= MERGE_GAP) break;
                        cursor++;
                    }
                    output.writeInt(start); output.writeInt(end - start);
                    output.writeBytes(payload, start, end - start); spans++;
                    if (output.readableBytes() + MIN_SAVING >= payload.length) return null;
                }
                output.setInt(countPosition, spans);
            }
            if (output.readableBytes() + MIN_SAVING >= payload.length) return null;
            byte[] result = new byte[output.readableBytes()]; output.readBytes(result); return result;
        } finally { output.release(); }
    }

    private static byte[] restore(byte[] base, byte[] body) {
        ByteBuf input = Unpooled.wrappedBuffer(body);
        try {
            require(input.readableBytes() >= 5, "truncated delta");
            int length = input.readInt(), mode = input.readUnsignedByte();
            require(length >= 0 && length <= MAX_PAYLOAD, "invalid restored length");
            byte[] result;
            if (mode == 0) {
                require(length == base.length && input.readableBytes() >= 4, "invalid span delta");
                int spans = input.readInt();
                require(spans >= 0 && spans <= input.readableBytes() / 9, "invalid span count");
                result = base.clone(); int previousEnd = 0;
                for (int index = 0; index < spans; index++) {
                    require(input.readableBytes() >= 8, "truncated span");
                    int start = input.readInt(), size = input.readInt();
                    require(start >= previousEnd && size > 0 && start <= length && size <= length - start
                        && size <= input.readableBytes(), "invalid span range");
                    input.readBytes(result, start, size); previousEnd = start + size;
                }
            } else {
                require(mode == 1 && input.readableBytes() >= 8, "unknown or truncated delta mode");
                int prefix = input.readInt(), suffix = input.readInt();
                require(prefix >= 0 && suffix >= 0 && prefix <= length && suffix <= length - prefix
                    && prefix <= base.length && suffix <= base.length - prefix, "invalid prefix or suffix");
                int middle = length - prefix - suffix;
                require(input.readableBytes() == middle, "invalid middle length");
                result = new byte[length];
                System.arraycopy(base, 0, result, 0, prefix);
                input.readBytes(result, prefix, middle);
                System.arraycopy(base, base.length - suffix, result, length - suffix, suffix);
            }
            require(!input.isReadable(), "trailing delta data");
            return result;
        } finally { input.release(); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
