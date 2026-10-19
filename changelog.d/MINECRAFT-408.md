bump: minor

### Changed
- The isometric F5 perspective now requests 4x vanilla's base detached
  distance (16 blocks unobstructed by default, up from 12), and pivots
  around a point 4 blocks below the player's eye instead of the eye itself.
  Wall/roof/terrain collision still shortens the actual distance exactly as
  before -- including right down to standing on normal ground, where the
  lowered pivot itself sits inside solid terrain. The camera distance is
  still scaled rather than replaced, so any future zoom/config feature
  still composes. Every other F5 perspective is unaffected.
