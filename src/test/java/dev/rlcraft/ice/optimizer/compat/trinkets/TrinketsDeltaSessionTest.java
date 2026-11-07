package dev.rlcraft.ice.optimizer.compat.trinkets;

import static org.junit.Assert.*;

import dev.rlcraft.ice.optimizer.compat.trinkets.TrinketsDeltaSession.Frame;
import dev.rlcraft.ice.optimizer.compat.trinkets.TrinketsDeltaSession.Key;
import dev.rlcraft.ice.optimizer.compat.trinkets.TrinketsDeltaSession.Receiver;
import dev.rlcraft.ice.optimizer.compat.trinkets.TrinketsDeltaSession.Sender;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.Random;
import org.junit.Test;

public final class TrinketsDeltaSessionTest {
    private static final Key A = new Key(42, 2, 3);
    private static final Key B = new Key(42, 2, 4);
    private static final Key C = new Key(43, 2, 3);

    @Test public void everyFrameReconstructsIncludingUnchangedMessagesAndSeparatedEdits() {
        Sender sender = new Sender(12, 4, 8192); Receiver receiver = new Receiver(4, 8192);
        byte[] payload = data(1024, 7);
        Frame first = sender.encode(A, payload);
        assertTrue(first.full); assertArrayEquals(payload, receiver.decode(wire(first)));
        Frame same = sender.encode(A, payload);
        assertFalse(same.full); assertEquals(first.sequence, same.baseRevision);
        byte[] repeated = receiver.decode(wire(same));
        assertNotNull("identical messages still have to be delivered", repeated);
        assertArrayEquals(payload, repeated);
        payload[3]++; payload[9]++; payload[900]++;
        Frame edited = sender.encode(A, payload);
        assertFalse(edited.full); assertTrue(edited.bodyLength() < 100);
        assertArrayEquals(payload, receiver.decode(wire(edited)));
    }

    @Test public void insertionDeletionAndNoUsefulCompressionChooseCorrectRepresentations() {
        Sender sender = new Sender(1, 4, 8192); Receiver receiver = new Receiver(4, 8192);
        byte[] initial = data(600, 9); accept(sender, receiver, A, initial);
        byte[] inserted = new byte[607];
        System.arraycopy(initial, 0, inserted, 0, 250);
        Arrays.fill(inserted, 250, 257, (byte) 99);
        System.arraycopy(initial, 250, inserted, 257, 350);
        Frame add = sender.encode(A, inserted); assertFalse(add.full);
        assertArrayEquals(inserted, receiver.decode(wire(add)));
        Frame remove = sender.encode(A, initial); assertFalse(remove.full);
        assertArrayEquals(initial, receiver.decode(wire(remove)));
        byte[] unrelated = data(600, 15);
        Frame full = sender.encode(A, unrelated); assertTrue(full.full);
        assertArrayEquals(unrelated, receiver.decode(wire(full)));
        accept(sender, receiver, A, new byte[0]);
        assertTrue(sender.encode(A, new byte[0]).full);
    }

    @Test public void independentRecipientsNeverUseEachOthersBaseline() {
        Sender one = new Sender(1, 4, 8192), two = new Sender(2, 4, 8192);
        Receiver first = new Receiver(4, 8192), second = new Receiver(4, 8192);
        byte[] payload = data(800, 20);
        accept(one, first, A, payload);
        payload[30]++;
        Frame oldRecipient = one.encode(A, payload), newcomer = two.encode(A, payload);
        assertFalse(oldRecipient.full); assertTrue(newcomer.full);
        assertArrayEquals(payload, first.decode(wire(oldRecipient)));
        assertArrayEquals(payload, second.decode(wire(newcomer)));
        Frame next = two.encode(A, payload); assertFalse(next.full);
        assertArrayEquals(payload, second.decode(wire(next)));
    }

    @Test public void lruAndByteBudgetsEvictTheSameEntriesOnBothSides() {
        for (int[] budget : new int[][] { { 2, 4096 }, { 8, 600 } }) {
            Sender sender = new Sender(5, budget[0], budget[1]);
            Receiver receiver = new Receiver(budget[0], budget[1]);
            byte[] payload = data(256, 12);
            accept(sender, receiver, A, payload); accept(sender, receiver, B, payload);
            Frame touched = sender.encode(A, payload); assertFalse(touched.full); receiver.decode(wire(touched));
            accept(sender, receiver, C, payload);
            Frame evicted = sender.encode(B, payload); assertTrue(evicted.full);
            assertArrayEquals(payload, receiver.decode(wire(evicted)));
            Frame retained = sender.encode(C, payload); assertFalse(retained.full);
            assertArrayEquals(payload, receiver.decode(wire(retained)));
        }
    }

    @Test public void oversizedUncachedFramesRemoveOnlyTheirOwnPriorBaseline() {
        Sender sender = new Sender(7, 2, 500); Receiver receiver = new Receiver(2, 500);
        byte[] small = data(200, 1), large = data(700, 2);
        accept(sender, receiver, A, small); accept(sender, receiver, B, small);
        Frame oversized = sender.encode(A, large); assertTrue(oversized.full);
        assertArrayEquals(large, receiver.decode(wire(oversized)));
        Frame other = sender.encode(B, small); assertFalse(other.full); receiver.decode(wire(other));
        Frame again = sender.encode(A, large); assertTrue(again.full); receiver.decode(wire(again));
        Frame smallAgain = sender.encode(A, small); assertTrue(smallAgain.full); receiver.decode(wire(smallAgain));
    }

    @Test public void epochRotationForcesFullThenStartsAnIndependentHistory() {
        Sender old = new Sender(10, 4, 4096); Receiver receiver = new Receiver(4, 4096);
        byte[] payload = data(300, 6);
        accept(old, receiver, A, payload); accept(old, receiver, B, payload);
        Sender restarted = new Sender(11, 4, 4096);
        Frame first = restarted.encode(B, payload); assertTrue(first.full); assertEquals(1, first.sequence);
        assertArrayEquals(payload, receiver.decode(wire(first)));
        Frame previousKey = restarted.encode(A, payload); assertTrue(previousKey.full);
        assertArrayEquals(payload, receiver.decode(wire(previousKey)));
    }

    @Test public void returnedBytesAndInputMutationCannotCorruptLaterBaselines() {
        Sender sender = new Sender(3, 2, 4096); Receiver receiver = new Receiver(2, 4096);
        byte[] original = data(300, 9), mutable = original.clone();
        Frame frame = sender.encode(A, mutable); Arrays.fill(mutable, (byte) 0);
        byte[] received = receiver.decode(wire(frame)); assertArrayEquals(original, received);
        Arrays.fill(received, (byte) 0);
        Frame unchanged = sender.encode(A, original); assertFalse(unchanged.full);
        assertArrayEquals(original, receiver.decode(wire(unchanged)));
    }

    @Test public void lossDuplicationAndMissingBaselinesAreExplicitFailures() {
        Sender sender = new Sender(4, 2, 4096); final Receiver receiver = new Receiver(2, 4096);
        byte[] payload = data(300, 2);
        final Frame first = sender.encode(A, payload), second = sender.encode(A, payload);
        reject(new Runnable() { @Override public void run() { receiver.decode(second); } });
        assertArrayEquals(payload, receiver.decode(first));
        reject(new Runnable() { @Override public void run() { receiver.decode(first); } });
        assertArrayEquals(payload, receiver.decode(second));
        final Frame missing = sender.encode(A, payload);
        final Receiver wrongBudget = new Receiver(0, 0);
        wrongBudget.decode(first); reject(new Runnable() { @Override public void run() { wrongBudget.decode(second); } });
        reject(new Runnable() { @Override public void run() { new Receiver(2, 4096).decode(missing); } });
    }

    @Test public void corruptFramesDoNotAdvanceStateAndBoundAllocationBeforeReading() {
        Sender sender = new Sender(4, 2, 4096); final Receiver receiver = new Receiver(2, 4096);
        byte[] payload = data(300, 2); accept(sender, receiver, A, payload);
        payload[2]++;
        Frame good = sender.encode(A, payload);
        ByteBuf bytes = Unpooled.buffer(); good.write(bytes);
        try {
            // First span length, after the 45-byte frame and 13-byte delta prefix.
            bytes.setInt(58, Integer.MAX_VALUE);
            final Frame corrupt = Frame.read(bytes);
            reject(new Runnable() { @Override public void run() { receiver.decode(corrupt); } });
        } finally { bytes.release(); }
        assertArrayEquals(payload, receiver.decode(wire(good)));
        final ByteBuf malformed = Unpooled.buffer(); good.write(malformed);
        try {
            malformed.setInt(41, TrinketsDeltaSession.MAX_PAYLOAD + 1);
            reject(new Runnable() { @Override public void run() { Frame.read(malformed); } });
        } finally { malformed.release(); }
        final ByteBuf shortHeader = Unpooled.buffer().writeLong(1);
        try { reject(new Runnable() { @Override public void run() { Frame.read(shortHeader); } }); }
        finally { shortHeader.release(); }
    }

    @Test public void deterministicRandomStreamsRoundTripAcrossEvictionAndSizeChanges() {
        Random random = new Random(0x1ce);
        Sender sender = new Sender(42, 7, 4096); Receiver receiver = new Receiver(7, 4096);
        byte[][] values = new byte[11][];
        for (int index = 0; index < values.length; index++) values[index] = data(120 + index * 20, index);
        for (int step = 0; step < 1500; step++) {
            int entry = random.nextInt(values.length);
            byte[] value = values[entry].clone();
            if (step % 13 == 0) value = Arrays.copyOf(value, random.nextInt(1100));
            for (int change = 0; change < 3 && value.length > 0; change++) value[random.nextInt(value.length)]++;
            values[entry] = value;
            accept(sender, receiver, new Key(entry / 3, entry % 2, entry), value);
        }
    }

    @Test public void maximumPayloadAndDisabledCacheRemainBounded() {
        Sender sender = new Sender(1, 0, 0); Receiver receiver = new Receiver(0, 0);
        byte[] payload = data(TrinketsDeltaSession.MAX_PAYLOAD, 4);
        Frame first = sender.encode(A, payload); assertTrue(first.full);
        assertArrayEquals(payload, receiver.decode(wire(first)));
        Frame second = sender.encode(A, payload); assertTrue(second.full);
        assertArrayEquals(payload, receiver.decode(wire(second)));
        final Sender invalid = new Sender(1, 1, 4096);
        reject(new Runnable() { @Override public void run() { invalid.encode(A, new byte[TrinketsDeltaSession.MAX_PAYLOAD + 1]); } });
        assertEquals("rejected payload must not consume a sequence", 1, invalid.encode(A, new byte[0]).sequence);
    }

    private static byte[] data(int length, int seed) {
        byte[] bytes = new byte[length]; new Random(seed).nextBytes(bytes); return bytes;
    }

    private static Frame wire(Frame frame) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            frame.write(buffer); Frame restored = Frame.read(buffer); assertFalse(buffer.isReadable()); return restored;
        } finally { buffer.release(); }
    }

    private static void accept(Sender sender, Receiver receiver, Key key, byte[] bytes) {
        assertArrayEquals(bytes, receiver.decode(wire(sender.encode(key, bytes))));
    }

    private static void reject(Runnable operation) {
        try { operation.run(); fail("invalid stream must fail explicitly"); }
        catch (IllegalArgumentException expected) { }
    }
}
