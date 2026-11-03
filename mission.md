# Mission

`forgelet-swarmforge` is the shared Forgelet layer: the scripts, instructions,
templates, skills and composition helpers used by every forge in the family.

Its mainline target is the `forgelet` branch. Work stays in this project's
checkout, is tested here, and is committed and pushed to `forgelet`. The active
`forgelet-forge` host receives a release only by composing from that branch.

Layer work must never stop, restart or tear down the active forge or its
projects. Tests use fixture roots and sockets. Any release step is a separate,
explicit composition followed by read-only health checks.
