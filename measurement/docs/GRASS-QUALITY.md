# Deliver a grass assessment with inspectable evidence

The local worker produces measurements and the evidence needed to question them. Its outputs are
experimental: a successful process is not a validated mowing recommendation. The API is a local
research integration point, not a deployed production service.

## Local worker contract

Run from `measurement/`, after installing the app dependencies and fetching the pinned local
segmentation model with `node scripts/fetch-model.mjs`:

```sh
node scripts/assess-grass.mjs 20260814-174814 --out .inspect/quality-garden
node scripts/check-grass-quality.mjs .inspect/quality-garden
```

The positional value identifies a saved run. Every saved sampled frame is processed in order.
Depth inference is not rerun. Segmentation, geometry and reporting run on the local CPU. No other
repository is imported. A caller passes an id to this process and reads JSON; the subtree stays
round-trippable. A missing model cache produces explicit frame failures, never a remote download.

Without `--out`, stdout contains the full evidence JSON. With `--out`, stdout contains a compact
result and the directory contains `assessment.json`, `report.html`, and `SHA256SUMS`. Progress is
newline-delimited JSON on stderr. The report embeds preview images and the complete JSON; it
needs no server or Internet connection after download. The source JPEG digests refer to the
originals, not the resized preview images. Byte checksums establish integrity, not truth.

`--context request.json` accepts a request extension:

```json
{
  "context": {"rodovia": null, "sentido": null, "km": null, "capturado_em": null},
  "frameContext": {},
  "against": []
}
```

`rodovia` identifies the highway, `sentido` the travel direction, `km` its official kilometre
position, and `capturado_em` the capture timestamp. `frameContext` is keyed by the exact source
frame number and carries those four fields per frame. Unknown values remain null. A run location
is not copied into every cell: mapping road-local cells into surveyed highway coordinates remains
an integration task. Do not substitute local reconstructed distance for official highway km.

`against` accepts exact recorded observation or evidence ids, at most one per source frame.
Comparisons use matching frames and the same depth, ground, grid and support settings. They
report mask overlap at source resolution, missing cells, H95 changes and exploratory threshold
crossings. A clump annotation is not a whole-lawn reference; only fully annotated target regions
can establish precision and recall for the operational target. These comparisons do not establish
physical height accuracy. Existing annotations are read without alteration.

## Local app API

The Vite local API exposes the same worker through these routes:

| Request | Result |
|---|---|
| `POST /api/grass-quality` with `{runId, offsetM, context?, frameContext?, against?}` | 202 with job id |
| `GET /api/grass-quality/<id>` | Counted progress, elapsed time, done or failed status |
| `GET /api/grass-quality/<id>/assessment.json` | The complete evidence contract |
| `GET /api/grass-quality/<id>/report.html` | The self-contained review interface |
| `GET /api/grass-quality/<id>/SHA256SUMS` | Checksums of both artifacts |
| `DELETE /api/grass-quality/<id>` | Cancel and remove the temporary job |

Mutations require the same loopback origin and session nonce as the rest of the local API. One
CPU job runs at a time, at most four packets are retained, and each expires after one hour.
Stopping the dev server cancels active processes and removes their scratch directories. Downloads
are explicit; no evidence is automatically published or promoted to operational decisions.

In Advanced, **Measure automatically** runs this path. The app imports the exact assessment and
pixel evidence into the grass grid, with automatic masks under a separate target. Its human
brush targets remain intact. **Inspect report** opens the packet. Changing the run while work is
in progress prevents its masks from being applied to the newly selected video.

## What the quality fields establish

The report shows every source frame, the semantic mask and the retained depth pixels. Selecting
a cell exposes its per-frame height votes, support, final percentiles and disagreements. The
suggested evidence includes a representative frame, the strongest disagreement and weak support.
The camera-path reference is drawn as an assumption, not as a detected boundary.

`observedCellCoverage` is measured cells divided by cells receiving retained grass points.
`intendedAreaCoverage` is null: the unsigned band folds both sides together and there is no
independently established corridor footprint. Empty masks and omitted cells mean unknown,
not short grass. High model probability and small plane-fit residuals are not calibrated accuracy.

`operationalStatus` remains `not-ready`. The blockers name missing physical validation, semantic
validation, local-ground validation, actual area coverage and an approved mowing policy. The
10 cm and 30 cm summaries are sensitivity examples inherited from dashboard fixtures, not Motiva
requirements. Human review records plausibility only; it never changes numeric results or
`validationStatus`. Review changes must be saved in the report or JSON to be retained.

## Proposed field and decision protocol — requires agreement before execution

Plan one field visit to supply independent segmentation, boundary and height evidence. Use a
stationary mounting position on the vehicle and record entire passes along a short, accessible
stretch, in the same direction, at a documented repeatable speed. Record mounting height, viewing
side, video dimensions, frame rate, weather, GNSS quality and capture timestamps. Include green,
brown and tall grass, exposed soil, shadow, fences and a change in shoulder slope. Mark matching
half-metre reference cells and independent visible road-edge points before recording. Preserve
all sampled frames, including poor views and occlusions. Repeat the pass to assess repeatability.
The final stretch, speed and duration must be agreed for the site rather than invented here.

There are three possible physical reference definitions. A tip-height ruler measures selected
plants, a light-contact plate measures compressed canopy height, and a surveyed soil/canopy
surface measures a spatial height distribution. They answer different questions. The proposed
reference for grid percentiles is a fixed sampling lattice with repeated vertical, non-compressing
height observations above independently established local soil; document unresolved occlusion,
reference repeatability and placement error. Agree sampling density and the exact canopy
observation before using it as truth. Keep calibration cells separate from evaluation cells.

Before the held-out scoring run, agree tolerances for missed target vegetation, false inclusion,
height error, repeat-pass disagreement, unmeasured area and mowing-category errors. Report each
scene condition and every failure, not only an overall mean. A roadside boundary is compared with
independent annotations or surveyed points; agreement with the camera-path approximation alone
is not a pass. If local ground cannot be established, decline operational measurement there.

A proposed mowing policy should use height severity together with the fraction or contiguous
length of the intended verge above an agreed threshold. A `trecho` is the road stretch receiving
that decision. Define its length, which percentile drives severity, the aggregation rule, minimum
coverage, and treatment of uncertainty near a threshold with Motiva. Preserve unknown stretches
as unknown, carry evidence links into the decision, and require acceptance before dashboard
promotion during the pilot. No existing fixture threshold is approved by this document.
