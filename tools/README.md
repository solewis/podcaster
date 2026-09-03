# tools

## icon_geometry.py

The launcher icon's glyph geometry, and the long-shadow construction, as the script that generated
`app/src/main/res/drawable/ic_launcher_foreground.xml` and `ic_launcher_monochrome.xml`.

The drawables are checked in and are what the app builds against - this is not part of the build.
It is here because the shadow is 19 generated polygons, and a hand-edit to the glyph that did not
also regenerate them would leave the shadow describing a microphone that no longer exists.

The construction, briefly: each part of the glyph is convex, and for a convex shape the region swept
along the light direction is exactly the convex hull of the shape and its translate. The union of
the pieces' sweeps is the sweep of the union. Taking one hull around the whole glyph instead would
bridge the gap between the capsule and the yoke and cast shadow through a hole the glyph does not
block.

`LauncherIconTest` checks the rendered result, including that the shadow falls to the lower right -
the sweep direction is baked into path data, so a sign error would otherwise change nothing that
fails.
