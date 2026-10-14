Build 19 indexed CUTOUT candidate — rejected for production admission

Full raw 28-byte vertex identity and native pixel checks passed. Full-game v9
survival measured 164.568 FPS versus two no-ICE runs at 182.069 and 194.207 FPS.
Combat measured 200.333 versus no-ICE 231.274 FPS, with stochastic opponent/view
differences. These data fail the user's FPS and tail-latency target.

CPU preparation on 1015 captured meshes: ordinary copy/normalize 14.857 ms;
index encoding 71.1081 ms; encoding+normalization 74.5453 ms. These are local
costs, not a causal decomposition of full-game regression.

The production path has been removed. The bounded encoder and tests are retained
here only for algorithm research. The metadata/native arena tests target the
frozen build19 implementation, not the restored production implementation.
Full build19 edited terrain source snapshot:
build/fps-target/129-build19/source-snapshot.zip
Frozen artifact SHA256:
BB1800965947B74FC7D308CC9DBFE0446C53CF183F6623F1B2AA8CFEE660AB8D

Known limit of the candidate: standard/fixed primitive restart was exercised;
the separate NV_primitive_restart client state was not. It also affects array
draws, so simply disabling it would not establish equivalence. Do not ship this
candidate or describe its native checks as universal GL-state compatibility.
Specification: https://registry.khronos.org/OpenGL/extensions/NV/NV_primitive_restart.txt
