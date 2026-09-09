# The depth endpoint, when this deployment reaches the depth stage through RunPod.
#
# The one resource in this stack that is a GPU. Everything else here scales to zero and costs
# nothing at rest; so does this, because `workers_min` is 0 and RunPod bills for a worker's
# lifetime rather than for the endpoint's existence. Creating it is free. The first job is not,
# and `AGENTS.md` asks for the user's agreement before each one.
#
# The provider is community-tier (`decentralized-infrastructure/runpod`, three commits, published
# 14 November 2025), which is the same footing as `kislerdm/neon` already in this stack. Its
# `runpod_endpoint` covers scaling, GPU selection and timeouts, and it stops there: RunPod's
# *template* — the image, the container disk and the environment the worker starts with — has a
# data source but no resource. So the template is created by
# `services/greenv-depth-runpod/provision.mjs`, called below during the apply itself so the whole
# deployment stays one command. Splitting it this way is not a preference; it is what the provider
# can do.

# Creating the template as part of reading it is a side effect in a data source, which is not
# ordinary — it is here because the alternative is a second command a person runs first and
# forgets once. The script is idempotent by name, so a plan that runs it twice gets the same id
# twice and changes nothing; and `terraform destroy` leaves the template behind, which is a
# template holding no GPU and costing nothing.
#
# Set `depth_template_id` to skip this entirely and name a template made some other way.
data "external" "depth_template" {
  count = local.provisions_depth_template ? 1 : 0

  program = ["node", "${path.module}/../services/greenv-depth-runpod/provision.mjs", "--json"]

  # The image the depth stage runs, pinned here beside the other three rather than defaulted
  # inside the script. `query` reaches the program on stdin and is visible in the plan, which an
  # image reference may be and a credential may not - the credentials stay in the environment.
  query = {
    image = var.depth_image
  }
}

resource "runpod_endpoint" "depth" {
  count = local.creates_depth_endpoint ? 1 : 0

  name        = "${local.runtime_name_prefix}-depth"
  template_id = local.depth_template_id

  compute_type = "GPU"

  # Every memory ceiling on record was measured on an L4's 22.03 GiB usable: 112 frames at 504 px
  # peaked at 21.28 GiB and 160 frames ran out (measurement/docs/vram-measurements.json). The
  # handler refuses a smaller card at startup rather than being killed mid-run, so naming the card
  # here is what keeps that refusal from ever being needed.
  gpu_type_ids = var.depth_gpu_type_ids
  gpu_count    = 1

  # Zero at rest, and one at work. A second worker is a second cold start rather than more
  # throughput: one segment is exactly one inference, two cannot be merged, and the depth service
  # holds a lock of its own.
  workers_min = 0
  workers_max = 1

  # The dial the whole bill hangs on. Segments arriving back to back from one drive ride a single
  # warm worker; a lone segment pays the entire tail. Short by default, to be raised deliberately
  # once a real drive shows how segments actually arrive.
  idle_timeout = var.depth_idle_timeout_seconds

  # A 112-frame run took 41 to 117 s of wall clock behind a ~64 s cold start (docs/AUTOMATIC-HEIGHT.md).
  # This is room for the worst recorded case and its start, and no more: a request that outlives it
  # is a fault, and paying past it buys nothing.
  execution_timeout_ms = 900000

  flashboot = true
}
