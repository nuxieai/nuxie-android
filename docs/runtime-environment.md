# Runtime environment

Each mounted Experience screen owns its global `env` instance. Environment
writes use the same serial runtime lane as native input and player advances.
Covered screens retain their own environment values even when screens share
a Journey's imported file and renderer.

The host supplies `reduceMotion` and local safe-area insets to older artifacts
through their existing runtime-value paths. It also writes `env.colorScheme`
when the artifact declares that enum slot and its matching label. The value
is `dark` only when the screen context's configuration reports
`UI_MODE_NIGHT_YES`; `UI_MODE_NIGHT_NO` and an unspecified mode use `light`.
Enum writes use the label's index in the artifact's own label order.

An Experience with dark color tokens carries a global schema named `Theme`.
Before the first player advance, including entry and layout measurement, the
host binds its authored instance named `dark` or `light` on every state-machine
player. If that name is absent, it creates the schema default, which uses the
first authored instance or generated defaults when there are no instances.
Each screen retains its created instances and reuses them when switching back.

Configuration changes write `env.colorScheme` and rebind `Theme` together on
the runtime lane. A submitted frame keeps its model revision until completion;
pending changes coalesce to the latest appearance before the next advance.
The surface's Choreographer loop draws the updated bindings on its next frame.
The Activity also forwards configuration changes to cached and transitioning
screens. Reattachment and becoming visible refresh the configuration before
re-entry, without replacing the Journey or resetting response values.

A file without a global `Theme` and without `env.colorScheme` receives no
appearance mutations. These values remain internal host inputs and introduce
no public SDK configuration or mutation API.
