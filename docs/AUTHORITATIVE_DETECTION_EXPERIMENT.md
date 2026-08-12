# Authoritative Detection Experiment

## Purpose

This experiment determines whether the Valkyrien Skies collision event path reports the target collision without relying on VS: Kinetic's Forge-side approximators.

The experiment is detection-only. It does not apply block damage or run the Forge ship-pair and ship-ground detectors.

## Enable

Run these commands in the Forge test instance with operator permissions:

```text
/vskinetic experiment authoritative on
/vskinetic experiment authoritative status
/vskinetic status
```

Enabling the mode does the following:

- Keeps `vsApi.collisionStartEvent`, `collisionPersistEvent`, and `collisionEndEvent` registered.
- Disables `ForgeShipPairCollisionDetector`.
- Disables `ShipGroundCollisionDetector`.
- Disables destructive damage and skips damage planning.
- Clears the fallback detectors' previous-contact state.
- Logs each authoritative event to `logs/latest.log` while the mode is enabled.

Record the status line before and after each test. Existing counters are cumulative for the current game session, so compare the counter deltas.

## Test Matrix

Perform each test after enabling the mode:

1. Dynamic ship into a static vanilla fence or wall.
2. Dynamic ship into static vanilla ground.
3. Dynamic ship into another dynamic ship.
4. High-speed ship-to-ship bounce that previously produced no registered collision.

After each collision, run:

```text
/vskinetic status
```

Then inspect `logs/latest.log` for:

```text
AuthoritativeExperiment
```

## Interpretation

An authoritative event was observed if one or more of these values increase after the test:

- `starts`
- `persists`
- `ends`
- `contacts`
- `captured`

The `lastAuthoritativeEvent` field and matching log line identify the raw event body IDs, contact count, dimension, and physics tick. The following log fields identify each contact:

- `p`: contact position
- `n`: contact normal
- `v`: contact velocity
- `s`: contact separation

`approximateImpacts`, `approximateTerrainImpacts`, `plansCreated`, and `plansApproximate` should not increase while the experiment is enabled.

## Disable

After testing, restore normal fallback behavior with:

```text
/vskinetic experiment authoritative off
/vskinetic experiment authoritative status
```

Disabling the mode re-enables both fallback detectors on the next server tick. It does not automatically re-enable destructive damage; use `/vskinetic damage enable` separately if needed.

## Results

Use the following table when recording results:

| Test | Starts | Persists | Ends | Contacts | Captured | Approximate delta | Result |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Ship -> fence/wall |  |  |  |  |  |  |  |
| Ship -> ground |  |  |  |  |  |  |  |
| Ship -> ship |  |  |  |  |  |  |  |
| High-speed ship bounce |  |  |  |  |  |  |  |

If the event counters do not change for a test, the next investigation target is the VS Core/Krunch event bridge rather than another Forge-side geometry approximation.

## Phase-1 Runtime Result

The listener registration path was tested in the Forge `VS2.4Temp4` instance with the installed VS `2.4.11` runtime. A freshly built and deployed `vs-kinetic-0.1.0.jar` loaded at `21:15:39`. Authoritative-only mode was enabled around `21:18:13` and confirmed with `authoritativeOnly=true` at `21:18:27`.

Four collision scenarios were exercised:

1. Dynamic ship into a static vanilla fence or wall.
2. Dynamic ship into static vanilla ground.
3. Dynamic ship into another dynamic ship.
4. A high-speed ship-to-ship bounce.

During the isolated run, `physicsTicks` increased from `7,572` to `18,729`, while `starts`, `persists`, `ends`, `contacts`, and `captured` remained zero. `lastAuthoritativeEvent` remained `null`, and `latest.log` contained no `AuthoritativeExperiment` entries. Fallback detector candidate counters stopped increasing after authoritative-only mode was enabled.

The jar load, mode toggle, physics tick progression, cumulative status inspection, and experiment logger were therefore verified independently. The result is that no authoritative collision callbacks were observable for these contacts in this runtime. This localizes the next investigation to the VS Core/Krunch bridge or to collision-event production below the public callback layer; it does not establish which of those two layers is at fault.

The Forge build now also includes a read-only raw bridge probe. It inspects the concrete VS Core pipeline's three collision maps without clearing or emitting them and reports `rawProbeTicks`, `rawEvents`, `rawProbeFailures`, and `lastRawProbeFailure` in `/vskinetic status`.

## Developer Clarification

The VS developers clarified the backend behavior for VS 2.4.11:

- `vsApi.collisionStartEvent` is emitted only by the PhysX backend.
- `vsApi.collisionPersistEvent` should work on all physics backends, including Krunch.
- For Krunch, `collisionPersistEvent` is the recommended supported callback for capturing collisions.
- `collisionEndEvent` was not confirmed as a backend-independent capture path.

The listener registration used by this addon is therefore the correct Krunch capture path:

```kotlin
vsApi.collisionPersistEvent.on(CollisionCapture::onPersist)
```

The callback exposes the collision dimension, both body IDs, and contact points. Each contact point provides position, normal, velocity, and separation. A persistent contact should be tested by holding two bodies together across multiple physics ticks; a brief high-speed impact may separate before a persist event can be observed.

The remaining issue is not which public API to use. In the tested Krunch runtime, `collisionPersistEvent` still produced zero callbacks during the controlled tests. The next runtime test should use a deliberately sustained ship-to-ship or ship-to-wall contact and compare `persistEvents` with the raw bridge telemetry.

## PhysX Runtime Result

The active `test` world was restarted with the VS Core backend changed from `KRUNCH_CLASSIC` to `KRUNCH_PHYSX` in `saves/test/serverconfig/valkyrienskies/vs-core-server.toml`.

The authoritative-only test then produced the following status at `22:18:25`:

```text
authoritativeOnly=true
starts=18
persists=23477
ends=0
contacts=194625
captured=77
rawEvents=571
rawProbeFailures=0
```

The latest authoritative event was a `PERSIST` event for body IDs `110` and `123` with `15` contacts. Since fallback detectors and damage planning were disabled, these records came from the VS authoritative event path. This confirms that the listener registration and contact capture code work with PhysX. It also confirms that `collisionStartEvent` is PhysX-specific as the developers described.

The observed backend comparison is:

| Backend | Starts | Persists | Result |
| --- | ---: | ---: | --- |
| `KRUNCH_CLASSIC` | 0 | 0 in the controlled test | No public authoritative callbacks observed |
| `KRUNCH_PHYSX` | 18 | 23,477 | Authoritative collision capture works |

## PhysX Tradeoffs

Using PhysX is viable for this addon, but it has tradeoffs:

- It is a different physics backend, so ship motion, solver behavior, contacts, and collision response can differ from `KRUNCH_CLASSIC`.
- PhysX depends on native backend support and can fail to initialize if the required native library is unavailable or incompatible with the platform.
- PhysX may have different performance characteristics, especially with many ships, complex collision shapes, high `physicsSubSteps`, or high physics thread counts.
- Switching an existing world backend should be tested carefully because the serialized VS physics pipeline and current body state were created under the previous backend. Restarting is required, and a backup of the world is prudent before extended testing.
- Collision events are now confirmed to work with PhysX, but this does not prove that every contact category or event lifecycle behaves identically across all backends.

For the current addon goal, PhysX provides the working authoritative collision callback path. Keep `collisionPersistEvent` as the main capture listener, and treat `collisionStartEvent` as an optional PhysX-only enhancement. Before making PhysX the required backend, test normal ship handling, terrain interaction, ship-to-ship response, performance, and world reload behavior against the intended gameplay setup.
