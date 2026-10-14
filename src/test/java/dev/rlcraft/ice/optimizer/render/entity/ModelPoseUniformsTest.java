package dev.rlcraft.ice.optimizer.render.entity;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ModelPoseUniformsTest {
    @Test public void unchangedSessionsKeepLinkedProgramValuesAndOnlyChangedSlotsUpload() {
        Recording writer = new Recording();
        ModelPoseUniforms uniforms = new ModelPoseUniforms(writer);
        for (int i = 0; i < 19; i++) uniforms.set(i, 100 + i, i & 1);
        assertEquals(19, writer.calls);
        for (int pass = 0; pass < 100; pass++)
            for (int i = 0; i < 19; i++) uniforms.set(i, 100 + i, i & 1);
        assertEquals(19, writer.calls);
        uniforms.set(7, 107, 9);
        assertEquals(20, writer.calls); assertEquals(107, writer.location); assertEquals(9, writer.value);
        uniforms.set(7, 107, 1);
        assertEquals(21, writer.calls);
        uniforms.set(18, 118, 32);
        assertEquals(22, writer.calls); assertEquals(32, writer.value);
    }

    @Test public void newLinkedProgramStartsWithoutAnyAcceptedValue() {
        Recording writer = new Recording();
        ModelPoseUniforms uniforms = new ModelPoseUniforms(writer);
        for (int i = 0; i < 19; i++) uniforms.set(i, i, 0);
        uniforms.reset();
        for (int i = 0; i < 19; i++) uniforms.set(i, 50 + i, 0);
        assertEquals(38, writer.calls); assertEquals(68, writer.location);
    }

    @Test public void failedNativeChangeDoesNotPoisonPreviouslyUploadedValues() {
        Recording writer = new Recording();
        ModelPoseUniforms uniforms = new ModelPoseUniforms(writer);
        uniforms.set(0, 7, 1);
        writer.failure = new IllegalStateException("native submission");
        try { uniforms.set(0, 7, 2); fail("must propagate"); }
        catch (IllegalStateException expected) { assertSame(writer.failure, expected); }
        writer.failure = null;
        uniforms.set(0, 7, 1);
        assertEquals(2, writer.calls);
        uniforms.set(0, 7, 2);
        assertEquals(3, writer.calls); assertEquals(2, writer.value);
    }

    private static final class Recording implements ModelPoseUniforms.Writer {
        int calls, location, value;
        RuntimeException failure;
        public void set(int location, int value) {
            calls++;
            if (failure != null) throw failure;
            this.location = location; this.value = value;
        }
    }
}
