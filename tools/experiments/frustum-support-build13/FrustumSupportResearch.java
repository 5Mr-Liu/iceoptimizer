import java.util.Arrays;

/** Offline check of IEEE-754 support reduction; never packaged in ICE. */
public strictfp class FrustumSupportResearch {
    static float minNumber(float a, float b) { return a < b || b != b ? a : b; }
    static boolean reference(float[] v, boolean all) {
        boolean result = all;
        for (int i = 0; i < 8; i++) {
            boolean inside = !(((v[i & 1] + v[2 + ((i >> 1) & 1)])
                + v[4 + ((i >> 2) & 1)]) + v[6] <= 0.0f);
            result = all ? result && inside : result || inside;
        }
        return result;
    }
    static boolean reduced(float[] v, boolean all) {
        float x = all ? minNumber(v[0], v[1]) : Math.max(v[0], v[1]);
        float y = all ? minNumber(v[2], v[3]) : Math.max(v[2], v[3]);
        float z = all ? minNumber(v[4], v[5]) : Math.max(v[4], v[5]);
        return !(((x + y) + z) + v[6] <= 0.0f);
    }
    public static void main(String[] args) {
        float[] values = {Float.NEGATIVE_INFINITY, -Float.MAX_VALUE, -1, -0.0f,
            0.0f, Float.MIN_VALUE, 1, Float.MAX_VALUE, Float.POSITIVE_INFINITY, Float.NaN};
        float[] v = new float[7];
        int failures = 0;
        for (int n = 0; n < 10000000; n++) {
            int code = n;
            for (int j = 0; j < 7; j++) { v[j] = values[code % 10]; code /= 10; }
            for (boolean all : new boolean[]{false, true}) {
                if (reference(v, all) != reduced(v, all)) {
                    System.out.println("MISMATCH all=" + all + " values=" + Arrays.toString(v));
                    if (++failures == 5) return;
                }
            }
        }
        System.out.println("PASS 20000000 product/exception combinations");
    }
}
