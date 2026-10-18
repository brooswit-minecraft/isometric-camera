bump: minor

### Changed
- The isometric F5 perspective now requests 4x vanilla's base detached
  distance (16 blocks unobstructed by default, up from 12), and pivots
  around a point 4 blocks below the player's eye instead of the eye itself
  -- both the unobstructed distance and wall/terrain collision handling are
  measured from that lowered point. The camera distance is still scaled
  rather than replaced, so any future zoom/config feature still composes.
  Every other F5 perspective is unaffected.
