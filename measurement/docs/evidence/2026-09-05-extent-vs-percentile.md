# Extent and the grid percentiles on the one fixture with a tape truth

### Extent is the only estimator this project has ever graded, and it wins here — 2026-09-05

**On the taped clump in `Test_Grass2`, the extent measurement reads 0.9983 m against a 0.980 m
tape (+1.9%). The grid's H95, computed on the identical human mask and the identical retained
points, reads 1.1769 m (+20.1%).** The gap is not noise and not a percentile problem. It
decomposes into two convention choices, both of which extent cancels and H95 cannot:

| Term | Size on this fixture | Why |
|---|---|---|
| Pedestal | +0.114 m (plane-normal) | The clump grows from a raised bed 0.114 m above the fitted plane. Extent subtracts its own base; H95 measures from the plane. |
| Axis | ×1.0904 | The grid divides the plane-perpendicular distance by `verticalScale` = `dot(normal, up)` = 0.9171 to measure along gravity. `measureVerticalExtent` does not; it measures along the plane normal. |

Recomposed: `(0.9983 + 0.1140) / 0.917132 = 1.2128 m`, against the 1.1769 m measured directly
(P95 sits just below the very top of the clump). The identity holds to about 4 cm.

**Every graded trial in this repository is an extent.** All 17 recorded observations across
`20260811-161356-d387ec`, `20260814-174520-eebd17` and `20260814-174814-b245bc` carry
`mode: "vertical_extent"` and `rulerKind: "extent"`. There is no graded height-above-plane
measurement anywhere, so `MEASUREMENTS.md`'s ±0.2% to ±2% record on brush targets is evidence
about extent alone and says nothing about H95.

**Evidence.** `node scripts/assess-grass.mjs 20260814-174814 --out <dir>` at offsets 2, −4 and −6,
all local CPU on cached weights, no cloud. The plane is `fitGroundPlaneRobust` on the recorded
GLB: normal `(0.003314, 0.927588, 0.373589)`, offset `0.546446`, tilt 23.489°, RMSE 17.1 mm,
27.8% inliers. Both estimators were run on one point set built from the exact recorded brush
`grass-exe1-wjhp:20260814-174814-b245bc#2` (21,135 painted pixels at 576×1024), resampled
nearest-neighbour to the 280×504 depth grid, eroded by 2 and filtered at the frame's recorded
confidence threshold 2.6749 — which reproduces the recorded `pointCount` of 4164 exactly.

**One reproduction gap, stated rather than hidden.** From that same mask and point count, this
session's re-derivation of extent gives 1.0616 m, not the recorded 0.9983 m. The recorded ruler
endpoints (0.1140 and 1.1122 plane-normal metres) sit inside the endpoints this session computed
(0.0758 and 1.1374), so the app trims about 6 cm of tail that `selectEndpointEvidence` +
`measureVerticalExtent` alone do not. The trimming step was not identified. The comparison above
survives it: extent is +1.9% as recorded and +8.3% as re-derived, against H95's +20.1%.

### The automatic mask contains none of the graded object

**At the clump's own cell the pipeline reports H95 = 0.000 m for a plant taped at 0.980 m.**
At `--offset -4` the clump lands at road-local (13.92, 1.02) in cell (13.75, 1.25), which the grid
calls `measured` from 8 frames and 1083 samples, and gives H50, H90 and H95 all 0.0000. At
`--offset -6` the same cell abstains with 0 frames and 0 samples. The `terrain`-only Cityscapes
policy retains flat ground there and none of the clump, which is the 0% overlap of
`2026-09-05-class-fit.md` arriving at the end of the pipeline rather than in a diagnostic.

**The default corridor does not contain the graded object at all.** At the shipped `--offset 2`
the clump sits at 5.02 m from the derived road edge, just outside the 5 m band, and its
`alongRoadM` of 12.47 equals the polyline length exactly — clamped to the end. A sweep of offsets
from −6 to +6 in 0.5 m steps puts it inside the band and off the clamp only at −6 to −4; the
camera track ends beside the garden bed, so the clump is past the end of the travel for every
non-negative offset. Cell (12.25, 4.75) in the default run reports H95 0.0183 m and is not the
clump.

**Therefore the two estimators cannot yet be compared on automatic masks on this fixture**, and
the estimator question is downstream of the mask question. The numbers in the first section come
from a human mask precisely so the mask is not a variable.

**Not established here.** No SAM- or AOI-prompted mask was run through the pipeline. No lawn
height truth exists, so nothing here grades H95 against the quantity it was designed for — a
mown surface with no single top to tape. The 23.489° plane tilt was not investigated; the fit is
sound as a datum (2.01% of the cloud more than 35 mm below it, and local ground within 0.5 m of
the clump base sits at p2 −0.006 m, p10 +0.008 m), but a tilt that large deserves its own check
before the axis choice is settled on this clip alone.

### What the rewrite changed on the real run — 2026-09-05

**The default reading demoted the pipeline's tallest cell from 1.188 m to 0.391 m, because 0.798 m
of it was the bank the plant stood on.** Re-running `20260814-174814` at the shipped `--offset 2`
with the extent default, over the same 61 measured cells:

| Cell | `h95M` | `localGroundM` | `extent95M` | ground-contact voxels |
|---|---:|---:|---:|---:|
| (2.75, 4.75) | 1.188 | 0.798 | **0.391** | 57 |
| (1.75, 4.75) | 1.064 | 0.080 | **0.985** | 195 |
| (4.25, 4.75) | 1.022 | 0.681 | **0.341** | 19 |
| (8.25, 4.75) | 0.720 | 0.000 | **0.720** | 301 |

The ranking is not a rescaling: (1.75, 4.75) rises from second to first and the two cells above it
fall below four others. Three of the eight tallest plane-relative cells were mostly pedestal. The
cells whose readings moved most are also the ones with the fewest voxels touching their own
datum — 19 and 57 against 195 to 301 — which is what `groundContactVoxels` was added to expose,
though contact count also rises with total samples and this is one run, not a validated detector.

`h95M − localGroundM === extent95M` held for every measured cell in both the `--offset 2` and
`--offset -4` runs. `node scripts/check-grass-quality.mjs` reports `checksums: pass`,
`reportMatchesJson: true`, `maskDigests: pass` on the packet.

**The taped clump still reads zero.** Cell (13.75, 1.25) at `--offset -4`, where the 0.980 m plant
stands: `extent95M` 0.000, `localGroundM` 0.000, from 8 frames and 1083 samples. The rewrite fixed
the datum, not the mask — the `terrain` class still retains the flat ground there and none of the
plant. Nothing in this section grades the extent default against a tape; that needs a mask that
contains the graded object first.
