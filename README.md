# isometric-camera

Minecraft Dungeons-like isometric F5 camera with screen-relative WASD movement.

NeoForge 1.21.1, Java 21. Mod ID: `isometriccamera`. MIT licensed.

## What it does

Pressing F5 normally cycles three perspectives (first person, third-person
back, third-person front) and wraps back to first person. This mod adds a
fourth: pressing F5 again from third-person front switches to a fixed-angle,
Minecraft Dungeons-like isometric view, and pressing it once more returns to
first person.

While the isometric perspective is active, W/A/S/D always move up/down/left/
right on screen, regardless of where the camera was previously facing —
this works by pinning the player's own yaw/pitch to the same fixed angle the
camera uses, so vanilla's yaw-relative movement reads as screen-relative.
Leaving the perspective (F5 again) restores normal third-person-front-style
mouse-look and movement immediately. Every other F5 perspective, and their
normal controls, is untouched.

See `IsometricCameraClientEvents` for the implementation and the
feasibility-check note on why this uses a fixed-angle third-person camera
rather than a true orthographic projection.

## Building

Requires a JDK 21 with `javac` on `JAVA_HOME`.

```sh
./gradlew build
```

## Releasing

Every PR that touches `src/` must add exactly one fragment under
`changelog.d/` (see `changelog.d/README.md`). Pushing to `main` with new
fragments tags a GitHub release automatically; nothing is committed back by
hand.
