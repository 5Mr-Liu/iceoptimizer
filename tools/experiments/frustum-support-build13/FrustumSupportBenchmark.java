package dev.rlcraft.ice.hooks;

import java.util.Random;

/** Standalone directional microbenchmark, never a full-game FPS claim. */
public final class FrustumSupportBenchmark {
    private static volatile int sink;
    public static void main(String[] args) throws Exception {
        NativeFrustumAdapterTest.BoxTest original = NativeFrustumAdapterTest.executable(false);
        NativeFrustumAdapterTest.BoxTest candidate = NativeFrustumAdapterTest.executable(true);
        float[][] p = {{1,0,0,256},{-1,0,0,256},{0,1,0,128},
            {0,-1,0,128},{0,0,1,256},{0,0,-1,256}};
        original.set(p,false); candidate.set(p,false);
        Random random = new Random(13);
        for (String kind : new String[]{"inside", "mixed", "early_reject"}) {
            double[][] boxes = new double[4096][6];
            for (double[] b : boxes) for (int axis=0;axis<3;axis++) {
                b[axis] = (random.nextDouble()-.5) * (kind.equals("inside") ? 96 : 1024);
                if(kind.equals("early_reject")) b[0] = -512-random.nextDouble()*128;
                b[axis+3] = b[axis]+16;
            }
            for(int w=0;w<12;w++) {run(original,boxes);run(candidate,boxes);}
            long[] old = new long[7], next = new long[7];
            for(int n=0;n<7;n++) {
                if((n&1)==0) {old[n]=run(original,boxes);next[n]=run(candidate,boxes);}
                else {next[n]=run(candidate,boxes);old[n]=run(original,boxes);}
            }
            java.util.Arrays.sort(old);java.util.Arrays.sort(next);
            System.out.printf(java.util.Locale.ROOT,
                "%s original_ns=%.3f candidate_ns=%.3f ratio=%.4f sink=%d%n",
                kind, old[3]/1048576.0,next[3]/1048576.0,(double)next[3]/old[3],sink);
        }
    }
    private static long run(NativeFrustumAdapterTest.BoxTest test, double[][] boxes) {
        int count=0;
        long start=System.nanoTime();
        for(int r=0;r<256;r++) for(double[] b:boxes)
            if(test.test(b[0],b[1],b[2],b[3],b[4],b[5])) count++;
        long time=System.nanoTime()-start;
        sink=count; return time;
    }
}
