Rejected Build13 research, 2026-10-13. Not production code.

Full implementation, six JUnit tests and build inputs are frozen in:
build/fps-target/130-native-build13-candidate/source.zip
Frozen JAR SHA256:
FF5EC5E3143F1F1BFC8C36CBBD22C0D3BC96CCCA137CC2B81AB70FF935FC576F

FrustumSupportResearch.java is a dependency-free Java 8 product algebra check:
javac -d <scratch-output> FrustumSupportResearch.java
java -cp <scratch-output> FrustumSupportResearch
It reports 20,000,000 corner/product comparisons. It is not a performance test.

FrustumSupportBenchmark.java uses NativeFrustumAdapterTest from the frozen
source. Compile in a separate scratch directory with the frozen three Java
files (NativeFrustumBounds, NativeFrustumAdapter, NativeFrustumAdapterTest),
the project hooks/test/early classpath, ASM 5.2, JUnit 4.13.2 and Hamcrest 1.3.
The default captured OptiFine class location is in that test. Override it with
-Dice.clippingHelper.class=<captured-class> if reproducing elsewhere.
Do not restore these files into production to repeat this rejected candidate.

Outcome: 400,000 actual-bytecode differential cases passed, but synthetic
all-inside input was slower. Fresh Build11/Build13 coarse diagnosis did not
demonstrate a net gain; entity workloads differed. Production changes removed.
Details: docs/ICE-1.0.30-FRUSTUM-SUPPORT-NOTES.txt and the frozen diagnostic report.
