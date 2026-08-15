package dev.rlcraft.ice.optimizer.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import dev.rlcraft.ice.optimizer.proxy.OptimizerServerProxy;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class ServerRuntimeIsolationTest {
    @Test
    public void dedicatedServerEntrypointsDoNotLinkClientPackages() throws Exception {
        assertNotNull(new OptimizerServerProxy());
        assertNoClientReference(OptimizerServerProxy.class);
        assertNoClientReference(ServerOptimizerRuntime.class);
        assertNoClientReference(OptimizerBridge.class);
    }

    private static void assertNoClientReference(Class<?> type) throws Exception {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        InputStream input = type.getResourceAsStream(resource);
        assertNotNull(input);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        } finally {
            input.close();
        }
        String constantPool = new String(output.toByteArray(), StandardCharsets.ISO_8859_1);
        assertFalse(constantPool.contains("net/minecraft/client"));
        assertFalse(constantPool.contains("dev/rlcraft/ice/optimizer/client"));
    }
}
