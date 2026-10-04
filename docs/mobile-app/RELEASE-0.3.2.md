# Claude Bot Mobile 0.3.2

Android version code 6. This visual follow-up replaces the early, conspicuous
edge fade reported in 0.3.1.

- Messages remain opaque in the reading area. A cached smootherstep gradient
  shades content by at most 16%, with zero slope at its endpoints.
- Softening is confined to the strips directly beneath panel text and controls;
  it does not start above the composer or below the header in the reading area.
  Source-atop blending preserves original bubble alpha and avoids edge halos.
- History reaches the physical window edges, including behind system bars.
  Header and composer controls retain their safe-area and keyboard insets, so
  there is no separate clipping line below the clock or above navigation.
- One reusable content recording supplies small top/bottom blur buffers. Older
  renderers without blur retain a narrow fade inside the panels for legibility.

Validation: all 25 existing real Compose/controller UI scenarios passed, plus
18 adaptive viewport/font scenario executions. Screenshots were inspected.
The panel-geometry regression now also asserts the actual window bounds; the
native IME is allowed to settle before one physical Send tap in the fixture.
Release R8 installation/startup is verified separately from benchmark UI tests.
As before, emulator observations do not certify physical-phone refresh rates,
and iOS runtime verification remains unavailable without full Xcode.

No backend or image-routing behavior changes in this patch.
