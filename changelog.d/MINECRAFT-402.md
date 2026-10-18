bump: minor

### Changed
- The isometric F5 perspective now frames roughly 1.5x farther from the
  player by default (scaling whatever camera distance would otherwise have
  been requested, so any future zoom/config feature still composes), showing
  more surrounding terrain while keeping the player comfortably visible.
  Wall/terrain collision handling for the camera is unchanged. Every other
  F5 perspective is unaffected.
