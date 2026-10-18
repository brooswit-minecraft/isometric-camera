bump: minor

### Changed
- The isometric F5 perspective now frames exactly 2x farther than the previous
  default (3x vanilla's base detached distance, up from 1.5x), an exact
  doubling of the prior ~6-block framing to ~12 blocks unobstructed. The
  camera distance is still scaled rather than replaced, so any future
  zoom/config feature still composes, and wall/terrain collision handling for
  the camera is unchanged. Every other F5 perspective is unaffected.
