# 2026-09-05 — automatic grass results carry inspectable quality evidence

The local CPU worker now processes every saved sampled frame, and delivers `assessment.json`, a
self-contained `report.html`, and `SHA256SUMS`. The app starts the same process and imports its
exact grid and pixel provenance. It exposes H50/H90/H95, per-frame votes, support, abstention and
H95 disagreement; the proposed review frames now include the largest disagreement. Existing
height calculations are unchanged. Generated masks retain native bytes for measurement and a
source-sized copy for display; brush correction drops the native copy so edits take effect.

The evidence separates original JPEGs, semantic masks and retained depth pixels. It includes
source/model/geometry digests, relevant implementation-file digests, frame identity, ground and
band assumptions, and nullable road metadata. Checksum verification establishes byte integrity,
not accuracy. Mask comparisons reject mismatched source frames, retain total misses as zero recall,
and retain cells absent from one side of a paired height comparison. Whole-lawn reference masks
and physical field accuracy have not yet been established.

Two complete saved runs were exercised on the local machine:

| Run | Frames processed | Measured / abstained cells | Observed-cell coverage | Maximum H95 frame spread | Local elapsed |
|---|---:|---:|---:|---:|---:|
| `20260814-174814-b245bc` | 94/94 | 61 / 17 | 78.2% | 107.1 cm | 47.1 s; repeat 30.7 s |
| `20260814-164826-0e4e4c` | 100/100 | 14 / 0 | 100% | 41.9 cm | 53.7 s |

The repeated garden run produced identical cell records and content fingerprint
`09a7c58f315d8474a7cc6d435e75738674ec237d9779af00832bc396922a993e`.
The lawn fingerprint is `d9104f39e8e7db12df90bd38065fa81513f334bb654131acdd56e350828e79f7`.
Timings include setup, segmentation and geometry; concurrent local verification affected some runs.
These are observations on one machine, not deployment latency guarantees.

The garden's source frame 10 visibly includes blue material in the terrain mask. Cell `3,9`
reports H95 106.4 cm but frame votes span 10.9–118.1 cm. The lawn review previously established
that brown grass is often missed; processing all frames does not repair that failure. Neither
run establishes the intended-area coverage: the unsigned band folds both sides together and its
road edge is assumed. Both packets therefore remain `not-ready`, review `pending`, and physically
`unvalidated`. A plausible-looking global floor is not validated local soil.

The report's acceptance checks require a reviewer and all three confirmations; rejection requires
a note. This is a visual plausibility record, never a change to physical validation. Semantic grass
is withheld from the single-object height path to avoid presenting a second, misleading reading.
The local API limits concurrent work and retains temporary packets for one hour. A direct local
check refused a second job, awaited cancellation of the child, and removed the cancelled job.

**Evidence.** `node scripts/assess-grass.mjs <run> --out .inspect/quality-<scene>`;
`node scripts/check-grass-quality.mjs .inspect/quality-{garden,garden-repeat,lawn}` each passed.
The browser-created garden repeat was retrieved from the local API and compared with the earlier
packet. `node scripts/collect-evidence.mjs` replayed all 26 recorded trials, each within 0.1 mm of
its stored reading (`.inspect/evidence/SUMMARY.md`). Browser observations and captures are recorded
in `design-review-log.md`; `.inspect/quality-garden-browser.png` shows the three evidence views.
A direct `file:` reopen was blocked by browser URL policy, so offline reopening is not claimed
as browser-verified. The saved HTML embeds the JSON and previews and matches its packet checker.
No cloud inference or deployment ran. The field and operations proposal is in `GRASS-QUALITY.md`.

The final harness passed 681 tests in 50 files, fixture smoke and every Python server contract
check (`VERGE_PY=.venv/bin/python ./scripts/verify.sh`, `.inspect/quality-verify.log`). Both app
production builds passed. The root dashboard also keeps six checked missing/invalid level values
out of its low-priority category; its unknown-height filter was observed in the browser.
