package dev.rlcraft.ice.hooks;

interface OptimizerBytecodeAdapter {
    byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) throws Exception;
}
