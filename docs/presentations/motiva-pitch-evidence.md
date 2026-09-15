# Motiva pitch evidence refresh — 14 September 2026

The presentation now uses the Desktop recordings and two saved roadside targets. Its main
accuracy claim is confined to those six manually selected trials: mean absolute error
0.9108197797 cm against two 10 cm references. It does not establish automatic field accuracy.

Branch: `feat/docs-motiva-pitch-evidence`, created from local `main` on 14 September 2026.
Source deck: `~/Desktop/GreenV-Motiva-Apresentacao-Final.pptx`.
Narrative source: `~/Desktop/pitch-greenv-final.md`.
Output: `output/motiva-pitch/GreenV-Motiva-Pitch-Evidencias.pptx` (local, not committed).
The source deck and Desktop assets were not overwritten.

## Evidence used

| Source | Trials in cm | Reference | Mean absolute error |
|---|---|---|---|
| rodovia_medida1.mp4, run 20260914-144411-5032ee | 8.51543, 8.78174, 8.19007 | 10 cm | 1.50425 cm |
| rodovia_movimento.mp4, run 20260914-143905-ce30bc | 10.28617, 10.47697, 10.18901 | 10 cm | 0.31739 cm |

The files are `~/verge-runs/<runId>/measurements/measurement-*.json`. Both targets use
`vertical_extent`, brush masks and three trials in one sitting, on frames 74 and 92 respectively.
These are two targets, not six independent stretches. Maximum absolute error: 1.80993 cm.
The reference for the movement target is described by the operator as approximately 10 cm.
No calibration or acceptance margin is inferred from these repetitions.

The appendix also includes all 15 currently saved trials found in this pass: these six and
nine indoor trials on table, PC tower and monitor in `RoomNewFixture.mp4`, run
`20260811-161356-d387ec`. Combined MAE is 0.5928261764 cm; indoor-only MAE is 0.3808304409 cm.
This mixed sample is not a claim of general accuracy. No garden, deleted or unsaved trial is added.

### Recording discrepancy

`Evidencia_Verge_studio1.mov` displays a live reading near 3.5 cm, with about -6.5 cm error,
for a different selection state. The saved graded movement target gives the numbers above.
The presentation retains that video as reconstruction inspection, explicitly distinguishes
its live selection from the saved trials, and does not count the live value in the aggregate.
`Evidencia_Verge_studio2.mov` also contains exploratory, unsaved selections.

## Automatic demonstration

The local CPU action “Measure automatically” was executed against saved run
`20260914-144411-5032ee`. It processed 87/87 frames, failed none, detected no target in three,
measured six cells and abstained on two. Local time was 31.420 seconds, excluding depth inference.
Content digest: `6a2d4af4a58317aa74e5698571e1b68436ec18e4b2fad7f73a04883efcf1168f`.
Private report: `.inspect/motiva-pitch-refresh/automatic/assessment.json` and `report.html`.
The source report was copied before its temporary job expires.

The mask uses the model's `terrain` class; the pictured retention includes pixels outside the
intended vegetation. Ground, intended-area coverage and physical heights remain unvalidated;
`operationalStatus` is `not-ready`, with seven explicit blockers. The screenshot demonstrates
execution and inspectability, not that the automatic result is suitable for a mowing decision.
Road metadata is null in this fixture; no location or capture date was invented.
No GPU service was deployed or invoked during this presentation task.

## Prediction demonstration

Read the local remote-tracking branch `origin/feat/vegetation-prediction`, commit `209c8ef`,
authored by Ryan Amorim de Castro Santana. No merge or execution of its service was needed.
The example is the actual saved forecast from
`services/greenv-vegetation-prediction/data/forecast/ranking_current.json`:

- `trecho_id`: `SP-021:sul:001500`.
- `as_of_date`: `2026-08-30`.
- Current synthetic height: 27.04 cm; critical threshold: 30 cm.
- Predicted time: 9.7 days; nominal 90% interval: 0 to 41.1 days.
- Vegetation provenance: synthetic. This is not a current field observation.

The native chart shows the predicted time and its interval; it does not invent a growth curve.
The interpretation is conditional on no new mowing intervention. Real measurement ingestion,
retraining and field validation remain future work, as described in the branch's technical brief.

## Other presentation boundaries

The actual graph view demonstrates explicit processing stages; it does not demonstrate a
working LiDAR adapter. Drone and LiDAR remain adaptation and validation paths. The closing
image is generated conceptual artwork about possible future inspection targets.
The camera-car photo supplied by the user is a setup reference, not a GreenV test vehicle.
The cost estimate retains the original deck's dated Cloud Run assumptions, and is explicitly
not a measured price for today's RunPod pipeline or a total operating cost.

## Presentation flow

Twelve visible slides: cover, capture, automatic processing, management, historical recap,
roadside scene 1, roadside scene 2, roadside accuracy, planning cost, modularity, prediction,
and broader inspection vision. Three hidden supporting slides retain the mixed-sample accuracy
and the original editable cost tables. Narration and source notes are embedded in the deck.

## Delivery verification

The final PPTX contains 15 slides, five embedded H.264 videos and two editable charts with
embedded data workbooks. All videos matched the prepared media byte-for-byte and decoded
without errors. Package, layout, font and chart checks passed; every exported slide was
rendered and visually inspected. Native Microsoft PowerPoint playback was not verified.
The three supporting slides are hidden during normal playback.

Deliverable: `output/motiva-pitch/GreenV-Motiva-Pitch-Evidencias.pptx`.
Large presentation assets remain local and are ignored by Git.
Final SHA-256: `a0c52184f3ba3487918d868748ebe2a14baab0c378671053624a903c423ec5b0`.

---

## Final deck — review pass of 14 September 2026

The review corrected the capture slide, rebuilt the cost slide around two capture paths, and put
the whole tape-graded record on the accuracy slide: **32 trials, 12 targets, 6 clips, mean absolute
error 2.11 cm**, with the roadside figure of 0.91 cm still the headline. A second pass replaced the
automatic-processing slide with an animation of the deployed pipeline measuring a driven clip.

Branch: `feat/docs-motiva-pitch-final`, from `main` at `777b390`.
Output: `output/motiva-pitch/GreenV-Motiva-Pitch-Final.pptx` (local, ignored), 11 visible slides and
3 hidden. The evidence deck above is its input and is left unchanged.
Build: `docs/presentations/motiva-pitch/build_final_deck.py`, run with any Python that has `lxml`.
It recomputes every accuracy figure from its source and stops if a rounded value on a slide no
longer matches. Icons in `docs/presentations/motiva-pitch/icons/` are Lucide 0.544.0 (ISC).
SHA-256 after the pass of 15 September: `5a5dcc80512f2d5801e163092df79f33cbb264b72f8d46354ef4b82cb6ba67a5`,
40.2 MB.

| # | Slide | Change |
|---|---|---|
| 2 | Captura em campo | Car photo removed. Phone video, "10 s" per segment, and four facts from `apps/mobile/README.md`: Android and iOS in Flutter; GPS and sensors; offline queue; a file leaves the phone only after the API accepts it |
| 3 | Gestão da operação | `greenv.matomomitsu.com` drawn as an address bar with a live badge, and the four deployed pieces from `docs/STATE-OF-THE-SYSTEM.md`: Cloudflare Pages, Azure Container Apps, RunPod, Neon and R2. The login page answered on 14 Sep; nobody signed in. On 15 Sep the recording was replaced by one that goes on to open a service order; see below |
| 4 | Do vídeo ao 3D | New. `projecao_2d3d.gif` on a background sampled from the GIF (`#0D0C12`). Animation confirmed by playing it in Keynote |
| 5 | Duas cenas medidas ao lado da trena | 15 Sep: the two roadside slides merged into one, both recordings side by side. See below |
| 6 | Sem ninguém descer do carro | New content for the old third slide, moved after the roadside scenes. See "Automatic measurement slide" below |
| 7 | Erro medido contra a trena | Roadside chart kept; panel added with every tape-graded trial on record |
| 8 | Quanto custa medir | Processing cost kept at US$ 0.62/km. Two capture paths: any phone, or a fixed roof installation with the user's car photo as reference |
| 10 | Previsão | Model, data and next step from Ryan's branch, replacing the one-line summary |
| 12 | Apoio · acurácia (hidden) | Rewritten to the 32-trial totals, which superseded the 15-trial aggregate |

The old third slide ("Processamento automático", a CPU report screenshot) is gone, and so, since
15 September, is the recap of the room and garden tests ("GreenV", the `recapVerge-Studio.mp4`
slide): the pitch is short of time, and the automatic measurement now carries that part. Speaker
notes carry the narration from `~/Desktop/pitch-greenv-final.md` and the sources. Slides whose notes
held minute ranges now name the pitch section instead, because the inserted slides made those
ranges wrong.

### Pass of 15 September

**The management video now shows a service order being opened, and its first 3.9 s are cut, because
until 2.0 s the recording shows the admin login form with the password legible.** The source is
`~/Desktop/NovoVideoWeb.mov` (2632 × 1540, 56.2 s). After the login it shows the overview, the map
with its level filter, the capture sessions, the measured stretches and one stretch's detail, then
opens `OS-ROÇ-202609-1001` for one stretch of the Rodovia Anchieta (p90 24 cm, level 2, medium
priority, 431 m², no team assigned yet), the order list and the teams page.

- **Cut.** At 3.9 s the dashboard has finished drawing and the page has not started to scroll yet.
  Keynote uses a video's frame at 0 s as its still, so the cut also resets the timestamps. Without
  that reset the first frame sat at 0.033 s and Keynote exported a black box.
- **Check.** A scan of every tenth of a second for the login page's purple panel finds it in
  0.0–1.9 s of the source and in no frame of the cut. The command is in the build script's
  docstring.
- **Output.** `output/motiva-pitch/Gestao-Web-OS.mp4`: 1640 × 960, 30 fps, 52.4 s, 9.27 MB, SHA-256
  `772f1a8febd798258b119da41fe63d753712068e5f3ae4865be85c92eccd9f14`. Its poster is its own first
  frame.
- **The source still holds the password.** Do not share `NovoVideoWeb.mov` itself.

**The two roadside slides are one.** "Vegetação ao lado da trena" (`rodovia_medida1`) and "A segunda
cena na rodovia" (`rodovia_movimento`) became "Duas cenas medidas ao lado da trena". The two
recordings play side by side, muted, each looping on its own length (22.4 s and 7.3 s). Under each is
the tape's 10.0 cm beside the mean of its three saved trials, 8.50 cm and 10.32 cm. The build
recomputes those figures from the packets and stops if they drift.

The recordings are live inspection with a mask painted on the spot, not the saved trials, and their
on-screen readings say so. `rodovia_medida1` climbs to 9.4 cm. `rodovia_movimento` starts at 3.5 cm
while the mask is painted and settles at 10.4 cm. The slide's footer and notes state that the numbers
come from the saved trials. This corrects the old note on the second scene, which gave only the
3.5 cm the recording passes through.

### Automatic measurement slide

**On `carro_em_movimento2.mp4`, driven in rain past a guardrail, a viaduct and a concrete barrier,
the deployed pipeline measured 360 half-metre cells over 238 m and refused 201 as structure and 528
as slope, with nobody painting a mask.** The heights are at the depth model's own scale, because the
clip has no telemetry to anchor it.

Packet: run `20260914-150840-e3efe7` (97 frames at 8 fps, 504 px, depth from the `verge-lab` Cloud
Run L4 on 14 Sep), measured on the local CPU the same day:

```
node measurement/scripts/assess-grass.mjs --stdin --out <packet> \
  < docs/presentations/motiva-pitch/automatic-request.json
```

The request carries the deployment's measurement settings from `infrastructure/variables.tf`:
`terrain,vegetation`, `ade20k-b4` at a floor of 0.4 with its eleven structure classes, automatic
band of 5.5 m, per-frame datum, slope rise 0.1 m, structure seen in 3 frames, past ends dropped,
exclusion next to `fence,wall,pole,building`, ground fallback, 3 m minimum track. It leaves out one
setting, `trackLengthM`, because there is no GPS track to take it from. The ADE20K B4 weights,
246 MB, were fetched with `node scripts/fetch-model.mjs ade20k-b4`. The run took 274 s.
`check-grass-quality.mjs` passes on the packet: checksums, mask digests, and a report that matches
the JSON. The packet is kept at `output/motiva-pitch/medicao-automatica-pacote/` and
`assessment.json` has SHA-256 `4467aa17889c69e31d2bfd41f26004cea7a282332a52fc59be32ee154beb460f`.

| Cells | Count |
|---|---:|
| Measured | 360 (`extent95M` p50 14.5 cm, p90 21.1 cm, max 47.0 cm) |
| Structure | 201, all between 35 and 107 m along the edge |
| Slope | 528 |
| Too few frames or samples | 1,414 — observed-cell coverage 14.4% |

**What limits those heights.** No scale anchor was applied. In the reconstruction the camera stands
0.73 m above the fitted plane and drives 220 m in 12.2 s, which is 65 km/h. A phone held at a car
window is usually higher than 0.73 m, so the heights may read low. An attempt to check the scale
against the guardrail and the barrier did not converge. Heights rise steadily with lateral distance
there, and the plane holds only 1.96% of the cloud, which is a wet road. No reading of this run has
been compared with a tape. The packet says `operationalStatus: not-ready`.

Frames drawn with each cell's own pixels show three things. The measured strip is the verge behind
the barrier. The viaduct abutment is refused as structure and the embankment as slope. In rain, the
grass model paints wet asphalt as vegetation, and that asphalt stays out of the band. Near the start,
some cells sit on the strip of grass at the foot of the guardrail.

Animation: `docs/presentations/motiva-pitch/render_automatic_measurement.py`, with Python, numpy and
Pillow.

- **Sources.** It reads only the packet and the saved run. It imports nothing from `measurement/`.
- **Left panel.** Each frame shows the depth pixels every cell used in that frame (the packet's
  `cells[].pixels`), coloured by the cell's verdict. Measured cells use the dashboard's level
  colours from `apps/web-core/src/utils/classification.js`.
- **Right panel.** Each cell's quad is placed by inverting the grid's `stationOf` on the packet's
  own road edge and plane, lifted to its `localGroundM`.
- **Timing.** A cell appears at its third voting frame. The backdrop is the run's GLB cloud, on the
  verge side only.
- **Order.** The animation opens on the finished stretch, so Keynote's still frame and the loop seam
  are both the result.

The output is 1600 × 716, 17.6 s, H.264 CRF 25, 8.3 MB (`output/motiva-pitch/Medicao-Automatica.mp4`,
SHA-256 `20db9c60f1a9e42bdb92d54e759d66a848108042de0df6b863757ad77c7195fb`). It is embedded like the
deck's other videos, with `repeatCount="indefinite"` added.

### Accuracy totals

Per-trial readings come from Verge Studio's replayed evidence (`.inspect/evidence/SUMMARY.md`,
26 trials, generated 3 Sep 2026) plus the six roadside packets in `~/verge-runs`. The nine saved
RoomNewFixture packets are the same trials as nine summary rows; the build checks this and counts
them once.

| Environment | Clips | Targets | Trials | Mean absolute error | Largest error |
|---|---:|---:|---:|---:|---:|
| Rodovia | 2 | 2 | 6 | 0.911 cm | 1.81 cm |
| Jardim | 2 | 4 | 8 | 1.549 cm | 3.65 cm |
| Interior | 2 | 6 | 18 | 2.764 cm | 8.25 cm |
| All | 6 | 12 | 32 | 2.113 cm | 8.25 cm |

References run from 10 cm to 2.10 m. The door clip of 4 Aug is the largest contributor; without it
the mean is 0.925 cm over 23 trials. Masks are hand-painted except the two Test_Grass trials. No
automatic reading has been graded against a tape, and the slide claims nothing about one.

`rodovia_medida2` (run `20260914-144756-810644`) and `rodovia_medida3` (run
`20260914-150153-7b55dc`) show a tape in frame and are reconstructed, but have no saved trials.
Grading them is what would add roadside targets.

### Cost slide sources

Processing is the evidence deck's Cloud Run L4 planning estimate, not a measured RunPod cost. The
Rodoanel Oeste example uses km 0–29.3 per direction, the stretch the GreenV map covers:
29.3 × US$ 0.615732 = US$ 18.04 per pass. Exchange rate R$ 5.16–5.18 on the morning of 14 Sep 2026
([InfoMoney](https://www.infomoney.com.br/mercados/dolar-hoje-abertura-fechamento-comercial-turismo-14092026/)).

Capture prices, consulted 14 Sep 2026:

- GoPro HERO13 Black, R$ 3,399 in the [GoPro Brazil store](https://gopro.com/pt/br/shop/cameras/buy/hero13black/CHDHX-131-master.html);
  5.3K60, built-in GPS, stabilisation. Promotions from R$ 3,099 are reported.
- Telesin triple suction mount, R$ 659.90 at [FunPro](https://www.funpro.com.br/produtos/suporte-ventosa-tripla-para-gopro-e-cameras-similares-telesin/)
  and R$ 701.90 at [KaBuM!](https://www.kabum.com.br/produto/388331/suporte-ventosa-tripla-para-gopro-e-cameras-similares-telesin).
- Phone car mounts, R$ 17–110 on [Amazon.com.br](https://www.amazon.com.br/suporte-veicular-ventosa/s?k=suporte+veicular+ventosa).
- Reference kit per vehicle: 2 × 3,399 + 2 × ~680 ≈ R$ 8,160, shown as "≈ R$ 8 mil".
  Not chosen: DJI Osmo Action 5 Pro, R$ 2,830–3,550, records GPS only through a separate remote
  ([DJI FAQ](https://www.dji.com/osmo-action-5-pro/faq)). Insta360 X5, from about R$ 4,700,
  records 360° footage, and the pipeline has only ever reconstructed ordinary phone video.

Two things the slide does not claim. A fixed mount's accuracy gain is expected, not measured.
And GreenV reads telemetry from its own app today: using a GoPro's GPS track needs an importer
that does not exist yet.

### Prediction slide sources

`origin/feat/vegetation-prediction` at `209c8ef`, by Ryan Amorim de Castro Santana,
`services/greenv-vegetation-prediction`:

- The model is a `RandomForestRegressor` with 300 trees, `min_samples_leaf=20` and 14 features.
  The forecast on the slide was made by `random_forest__keep_plus_candidate__framing_b`.
- All vegetation data is synthetic: 118 stretches of 500 m on the Rodoanel Oeste. The weather is
  real, from Open-Meteo, checked against NASA POWER, 2023-01-01 to 2026-09-01.
- On the synthetic test set, days-to-30-cm has a mean absolute error of 11.17 days. The best
  baseline scores 18.45 days, so the model is 39.4% better (`reports/model-card.md`).
- The 90% conformal interval averages about 55 days wide.

### Checks

- `scripts/office/validate.py` from the pptx skill passed, compared against the evidence deck, on 14
  and again on 15 September.
- Every visible slide was exported by Keynote and inspected, including the automatic slide's still;
  on 15 September all eleven again, after the merge. The embedded management and automatic videos
  match their files in `output/motiva-pitch/` byte for byte.
- The frustum GIF was seen on three different frames during Keynote playback.
- All visible text is Arial. Keynote's font warning comes from the Calibri theme fonts that the
  evidence deck already carried.
- Not checked: playback in Microsoft PowerPoint, which is not installed on this machine. No video
  was played in a slideshow, including the two looping side by side on the roadside slide. A test
  slideshow was stopped, because it took over the screen while the user was working in Keynote.
