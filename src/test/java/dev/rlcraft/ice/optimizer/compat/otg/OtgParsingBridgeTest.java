package dev.rlcraft.ice.optimizer.compat.otg;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import dev.rlcraft.ice.optimizer.ClientOptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.util.Locale;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class OtgParsingBridgeTest {
    private boolean previousEnabled;
    private boolean previousModule;

    @Before
    public void enableModule() {
        previousEnabled = OptimizerConfig.settings.enabled;
        previousModule = OptimizerConfig.settings.otgConfigParser;
        OptimizerConfig.settings.enabled = true;
        OptimizerConfig.settings.otgConfigParser = true;
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
        OptimizerRegistry.breaker(OptimizationModule.OTG_CONFIG_PARSER)
            .patchInstalled("synthetic", "test");
    }

    @After
    public void restoreModule() {
        OptimizerConfig.settings.enabled = previousEnabled;
        OptimizerConfig.settings.otgConfigParser = previousModule;
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
    }

    @Test
    public void fastParserMatchesOriginalForNestedAndMalformedInputs() {
        String[] inputs = {
            "", "   ", "1,2,3", " 1 , two , three ",
            "Block(1,2,3),NORTH,50", "a,(b,c,(d,e)),f", "a,,c,",
            "a,(b,c", "a,b),c", "a,（b,c）,d", "single"
        };
        for (String input : inputs) {
            assertArrayEquals(input, OtgParsingBridge.originalCommaSeparatedString(input),
                OtgParsingBridge.fastCommaSeparatedString(input));
            assertArrayEquals(input, OtgParsingBridge.originalCommaSeparatedString(input),
                OtgParsingBridge.readCommaSeparatedString(input));
        }
    }

    @Test
    public void lowercaseCacheTracksDefaultLocaleSemantics() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            assertEquals("block", OtgParsingBridge.lowercaseFunctionName("BLOCK"));
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals("ı", OtgParsingBridge.lowercaseFunctionName("I"));
        } finally {
            Locale.setDefault(original);
        }
    }
}
