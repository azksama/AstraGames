# Wine launch and rendering investigation — beta.21

## Submitted report

Source: `Astra-Wolf-1791731842756-19df043c-9efa-4549-a6e9-0fd03765fc18.txt`.
SHA-256: `6ccda966a110a3cf7e16f721972ed3f3e257601b1131699bea64de8bfaeca419`.
The private report and original game files are not redistributed here.

Observed in the report:

- Astra beta.20 uses **BALANCED**, not FAST; the environment explicitly sets conservative flags and disables esync. The old diagnostic wrongly suggested FAST from `rtl_rb_tree_put failed` alone.
- Wine service process `003c` encounters a null access in `nsiproxy.so/update_if_table` (lines 686–700, repeated in the bounded log). It is a network-interface service fault; this does not prove termination of the game process.
- The game continues through its own startup at 17:17:25. It prints a **TJS2 2.4.28 / KiriKiri Z** banner, loads `startup.tjs`, `xp3dec.tpm` and `KAGParser.dll` (lines 1804, 1838–1847). This report concerns a KiriKiri game selected for the Windows route, not a demonstrated Wolf game.
- A new invocation of the same executable appears with `-wsmute=never` (lines 1859–1867). At 17:17:26 Astra sees the initial loader's exit code **0**, throws before the first image and immediately kills the Wine server. A loader exit was being treated as the entire game session ending, even when the game relaunches itself.

The lifecycle correction supervises the game launch and tagged descendants. A random inherited launch token, same Android UID, exact Wine prefix/runtime identity, and exclusion of system service executables scope the check. Tools started by Astra do not inherit the token. A five-second empty interval accommodates a launcher handoff; a first-image timeout still prevents indefinite preparation. No process-name-wide kill or deletion of game data is added.

Residual uncertainty: the service fault and module errors can still indicate runtime incompatibility. Neither changing a profile blindly nor merely delaying shutdown proves this particular game is now compatible. Its original executable/data were not supplied for a full reproduction.

## Rendering changes

The previous texture updater unions every changed pixel into a single rectangle. Two animated sprites on opposite sides of a map can therefore copy and upload nearly the complete screen. This affects software-rendered Wine games even when there is only one visible X11 window, unlike the beta.20 hidden-window optimization.

The JNI copy path now records precise changed bounds in 64-pixel cells. The texture updater coalesces nearby regions into at most 32 uploads, with consistent snapshots taken under the drawable lock and GL transfers outside that lock. First/unknown/shared-buffer updates remain complete. No image scaling, frame skipping or lower FPS limit is used to claim a gain.

When most of a frame really changes (for example map scrolling), the next three writes use the fast full-copy path before checking for sparse changes again. This avoids repeatedly comparing a fully changing image. Overlapping in-place XCopyArea scrolls now use overlap-safe copies and row order.

## Verification targets and limits

- JVM region tests: isolated sprites, adjacent tiles, bounded batches without dropped pixels.
- Android SDK GLES/JNI readback: distant sprites on the same scanlines, clipping, next-frame changes during upload, overlapping map scroll.
- Android SDK benchmark: identical 1280×960 image, 40 animated sprites, 100 frames per pass in ABBA order. Measures JNI copy, snapshot and actual GLES completion; writes `wolf-dense-sprites-benchmark.txt`.
- All **9 texture-transfer instrumentation tests passed** on the Android SDK emulator, including distant-sprite consistency, in-place scrolling and the benchmark. Other release checks are recorded separately by the coordinated run.
- Software-emulator timings do not establish a constant physical-device FPS floor. CPU time inside the original Windows game, image decoding and Box64 remain separate costs. No 30/60 FPS guarantee is made.

Measured benchmark results (`build/wolf-research/beta21-dense-sprites-benchmark.txt`):

| Pass | Transfers | Median | P95 | Bytes for 100 frames |
| --- | --- | --- | --- | --- |
| A1 | Full reference | 2.1228 ms | 2.4682 ms | 491,520,000 |
| B1 | Separate regions | 1.7459 ms | 2.0169 ms | 29,565,600 |
| B2 | Separate regions | 1.6385 ms | 1.8122 ms | 29,565,600 |
| A2 | Full reference | 1.9648 ms | 2.2053 ms | 491,520,000 |

Mean of pass medians: 2.0438 → 1.6922 ms (**17.2% lower measured copy/upload/completion time**). Bytes transferred are **94.0% lower**, at identical resolution and checked pixel content. This measures the rendering path in an authored workload, not end-to-end FPS in the user's game.

Le test `WolfWineRelaunchRuntimeTest` a exécuté un exécutable original de vérification dans le vrai Wine : sortie 0 du parent, enfant surveillé pendant plus de six secondes, auxiliaire non comptabilisé, puis fin de session après sortie de l’enfant. Il ne reproduit pas les ressources privées du jeu KiriKiri signalé.
