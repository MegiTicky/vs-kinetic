# VS: Kinetic Implementation Plan

## 1. Document Purpose

This document defines the technical direction, implementation phases, data model, physics model, project structure, compatibility strategy, testing strategy, and performance constraints for **VS: Kinetic**.

VS: Kinetic is a Forge 1.20.1 addon for Valkyrien Skies 2. Its purpose is to turn ship collision contacts into controlled, physically motivated block damage. A ship striking terrain or another ship should consume kinetic energy according to the impact velocity, effective mass, contact geometry, and the properties of the blocks involved.

The design is intentionally an **energy-based penetration and destruction system**, not a real-time finite-element or deformable-body solver. VS2 and VS Core remain responsible for rigid-body motion and collision resolution. VS: Kinetic observes collision contacts, evaluates the impact, and applies bounded Minecraft block changes afterward.

## 2. Confirmed Initial Scope

The first release is planned around the following decisions:

- Minecraft 1.20.1.
- Forge as the initial runtime platform.
- Kotlin for implementation.
- A multiloader-capable project structure, even if Forge is the only initially released loader.
- Valkyrien Skies 2.4.x as the initial VS2 compatibility target, with the exact pinned version selected during project bootstrap.
- Ship-to-ship collision damage.
- Ship-to-ground collision damage.
- Damage to both sides of a ship-to-ship impact where the energy and material rules justify it.
- Material strength primarily derived from Minecraft blast resistance.
- Existing VS mass, friction, elasticity, and collision-shape information reused where useful.
- Formula-derived fallback values for properties that can be estimated safely.
- Datapack overrides for cases where formulas are not reliable or where pack authors need control.
- Claims and block-protection systems honored by default.
- Create, Create Big Cannons, and Clockwork blocks accounted for through their existing block and VS properties where possible.
- Medium-sized ships as the performance target: hundreds to a few thousand blocks, with strict per-tick processing limits.

The following are explicitly outside the first implementation target:

- Continuous mesh deformation.
- Per-block stress tensors or finite-element structural mechanics.
- Arbitrary destruction of block entities without compatibility rules.
- Replacing VS Core's broad-phase or narrow-phase collision solver.
- Guaranteeing perfect continuous collision detection at any velocity.
- Bypassing claims, protection plugins, or normal block-breaking permissions by default.
- Supporting every modded block entity or contraption behavior automatically.

## 3. Design Principles

### 3.1 Keep Rigid-Body Physics in VS2

VS2 already owns ship transforms, mass, inertia, velocity, contact generation, and collision response. VS: Kinetic must not duplicate those systems. The addon should consume public or addon-facing VS APIs and apply damage as a consequence of contacts.

### 3.2 Make Destruction Deterministic and Bounded

The same collision should produce approximately the same result on a server regardless of client frame rate. Collision events must be converted into immutable impact records, merged, sorted, and processed on the server game thread with explicit caps.

### 3.3 Prefer Existing Minecraft and VS Data

The default material model should use values already attached to blocks:

- `BlockState.getExplosionResistance(...)` as the primary strength/toughness analog.
- VS mass data for mass and density-related behavior where available.
- VS friction for tangential energy and sliding damage.
- VS elasticity for rebound and damage distribution adjustments.
- Minecraft collision shapes for contact area and exposed surface estimation.
- Block tags and datapack data for explicit exceptions.

New datapack properties should be added only when an existing value cannot produce a stable and understandable result.

### 3.4 Never Destroy Blocks Directly From the Physics Callback

VS physics callbacks may run in a different timing context from ordinary world mutation. The callback should only capture contact data and enqueue an impact. Block lookup, protection checks, block entity handling, drops, effects, and block removal belong on the server thread during a controlled game-tick processing stage.

### 3.5 Fail Safely for Unknown Mods

Unknown blocks should receive a conservative, formula-derived material profile. Unsupported block entities should be skipped rather than silently removed. A datapack must be able to override the behavior.

## 4. High-Level Architecture

```text
VS2 CollisionEvent
        |
        v
Collision Event Adapter
        |
        v
Impact Record Queue
        |
        v
Impact Aggregator / Deduplicator
        |
        v
Impact Evaluator
        |
        +--> Material Resolver
        +--> Ship/Terrain Coordinate Resolver
        +--> Energy and Contact Geometry Calculator
        +--> Protection Adapter
        +--> Block Entity Policy
        |
        v
Penetration Planner
        |
        v
Bounded Block Damage Queue
        |
        v
Server-Thread Block Damage Executor
        |
        +--> Vanilla/Forge block events and protection checks
        +--> Block removal and drops
        +--> Ship split/mass recalculation through VS2
        +--> Particles, sounds, game events, telemetry
```

The framework should be divided into six logical subsystems:

1. **Integration layer:** subscribes to VS2 events and Forge lifecycle events.
2. **Impact capture layer:** converts VS collision events into safe internal records.
3. **Physics model layer:** computes relative velocity, effective mass, energy, impulse proxies, and damage budgets.
4. **World analysis layer:** resolves contact blocks, local coordinates, exposed surfaces, material profiles, and protection status.
5. **Damage execution layer:** applies planned block damage with limits and normal server semantics.
6. **Presentation and diagnostics layer:** sounds, particles, debug overlays, metrics, and optional logging.

## 5. Project Structure

The implementation should use a Kotlin multiloader layout similar to VS2 and Clockwork, with common code separated from platform code.

```text
VS_Kinetic/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── common/
│   └── src/main/kotlin/com/example/vskinetic/
│       ├── VSKineticMod.kt
│       ├── VSKineticConfig.kt
│       ├── integration/
│       │   ├── VsCollisionIntegration.kt
│       │   ├── VsApiFacade.kt
│       │   ├── CreateIntegration.kt
│       │   ├── CbcIntegration.kt
│       │   ├── ClockworkIntegration.kt
│       │   └── ProtectionIntegration.kt
│       ├── collision/
│       │   ├── CollisionCapture.kt
│       │   ├── ImpactRecord.kt
│       │   ├── ImpactQueue.kt
│       │   ├── ImpactAggregator.kt
│       │   └── ImpactLimiter.kt
│       ├── physics/
│       │   ├── ImpactEnergyModel.kt
│       │   ├── RelativeMotion.kt
│       │   ├── ContactGeometry.kt
│       │   ├── PenetrationModel.kt
│       │   └── DamageBudget.kt
│       ├── material/
│       │   ├── MaterialProfile.kt
│       │   ├── MaterialResolver.kt
│       │   ├── MaterialDataLoader.kt
│       │   ├── MaterialDefaults.kt
│       │   └── MaterialCache.kt
│       ├── world/
│       │   ├── CollisionTarget.kt
│       │   ├── ShipCoordinateResolver.kt
│       │   ├── ContactBlockResolver.kt
│       │   ├── PenetrationPath.kt
│       │   └── BlockDamageExecutor.kt
│       ├── protection/
│       │   ├── ProtectionService.kt
│       │   ├── ForgeBreakPermissionService.kt
│       │   └── OptionalClaimAdapters.kt
│       ├── damage/
│       │   ├── DamagePlan.kt
│       │   ├── DamagePlanner.kt
│       │   ├── DamageOperation.kt
│       │   └── DamageResult.kt
│       ├── presentation/
│       │   ├── KineticEffects.kt
│       │   └── KineticDebugRenderer.kt
│       └── command/
│           └── KineticCommands.kt
├── forge/
│   └── src/main/kotlin/com/example/vskinetic/forge/
│       ├── VSKineticForge.kt
│       ├── ForgeEventRegistration.kt
│       ├── ForgeProtectionHooks.kt
│       └── ForgePlatform.kt
├── fabric/
│   └── reserved for the later Fabric implementation
├── common/src/main/resources/
│   ├── data/vskinetic/
│   │   └── tags/blocks/
│   └── assets/vskinetic/
├── forge/src/main/resources/
│   └── META-INF/mods.toml
└── docs/
    ├── MATERIALS.md
    ├── COMPATIBILITY.md
    └── DEBUGGING.md
```

The package name and metadata identifiers must be selected before bootstrap. The examples above intentionally use a placeholder package.

## 6. VS2 Integration

### 6.1 Dependency and Version Pinning

The initial build must pin:

- Minecraft 1.20.1.
- Forge 47.x compatible with the selected VS2 artifact.
- Kotlin for Forge version required by VS2's Forge environment.
- VS2 2.4.7 or the exact locally tested 2.4.x version.
- Matching VS Core API, internal, util, and implementation artifacts.

The addon should compile against the VS2 API and only use internal classes where no public route exists. Any internal dependency must be isolated behind `VsApiFacade` so future VS2 upgrades have a small migration surface.

### 6.2 Collision Event Registration

At common mod initialization, register a listener using the VS API collision-start event, following the usage pattern in Clockwork:

```kotlin
vsApi.collisionStartEvent.on { event ->
    collisionCapture.capture(event)
}
```

The exact event and callback types must be verified against the pinned VS2 artifact during project bootstrap. The listener must:

- Verify that the event belongs to a server-side physics world.
- Reject or record contacts from dimensions disabled in configuration.
- Copy the event's required values into an immutable `ImpactRecord`.
- Avoid block lookup and world mutation.
- Avoid retaining VS Core event objects beyond the callback.

### 6.3 Physics Tick Integration

Register a physics or server-level tick hook for diagnostics and any physics-only bookkeeping. The main damage execution should occur during a server-thread level tick after impact aggregation.

The implementation should not use `GameToPhysicsAdapter` to fake collision impulses. `GameToPhysicsAdapter` is useful for applying additional forces, but the first version should let VS2 resolve the rigid-body collision and only apply block damage. A later optional feature may use queued forces to model material failure impulses, but it must not destabilize base VS physics.

### 6.4 Ship and Ground Identification

The VS physics world has a ground body identifier per dimension. The integration layer must normalize every event into a pair of `CollisionTarget` values:

```kotlin
sealed interface CollisionTarget {
    data class Ship(val id: ShipId) : CollisionTarget
    data object Ground : CollisionTarget
}
```

The ground body is not treated as a normal loaded ship. It has no movable mass, but it does have terrain blocks and an effectively infinite or configured mass for reduced-mass calculations.

### 6.5 Contact Data Normalization

For each contact point, preserve at minimum:

- Dimension identifier.
- Ship ID A and ship ID B.
- Contact world position.
- Contact normal.
- Separation.
- Relative contact velocity.
- Contact point velocity if exposed by the event.
- Contact timestamp or physics tick identifier if available.

If the event exposes multiple contacts, normalize all of them and aggregate them later. Do not assume the first contact is representative of the whole collision.

## 7. Internal Data Model

### 7.1 `ImpactRecord`

`ImpactRecord` should be an immutable data class. It should contain identifiers and copied vectors rather than references to mutable VS objects.

Suggested fields:

```kotlin
data class ImpactRecord(
    val dimensionId: DimensionId,
    val bodyA: CollisionTarget,
    val bodyB: CollisionTarget,
    val contactPositionWorld: Vector3d,
    val normalWorld: Vector3d,
    val separation: Double,
    val relativeVelocityWorld: Vector3d,
    val physicsTick: Long,
    val sourceEventId: Long?
)
```

`sourceEventId` is optional. If VS does not provide one, the aggregator can derive a temporary collision key from the body pair, tick, and spatial contact bucket.

### 7.2 `MaterialProfile`

Suggested fields:

```kotlin
data class MaterialProfile(
    val strength: Double,
    val toughness: Double,
    val density: Double,
    val friction: Double,
    val elasticity: Double,
    val brittleness: Double,
    val penetrationMultiplier: Double,
    val damageMultiplier: Double,
    val canBreak: Boolean,
    val treatment: MaterialTreatment
)
```

The first release may collapse `strength` and `toughness` into fewer fields internally, but the conceptual distinction is useful:

- **Strength:** instantaneous resistance to penetration or failure.
- **Toughness:** energy absorbed before failure.
- **Density:** mass contribution and inertial/material behavior.
- **Friction:** tangential sliding and shear behavior.
- **Elasticity:** rebound and energy-return behavior.
- **Brittleness:** whether a failed block consumes a small amount of energy but spreads damage nearby.

`MaterialTreatment` should initially include at least:

- `NORMAL`: ordinary breakable block.
- `IMMOVABLE`: skip unless explicitly overridden.
- `FRAGILE`: low energy threshold, optional splash damage.
- `PROTECTIVE`: high resistance and conservative block-entity handling.
- `NO_DAMAGE`: never remove through kinetic damage.

### 7.3 Damage Operations

Damage planning must produce operations before modifying the world:

```kotlin
data class DamageOperation(
    val target: CollisionTarget,
    val worldPosition: BlockPos,
    val expectedState: BlockState,
    val energyCost: Double,
    val reason: DamageReason,
    val contactId: Long
)
```

The executor must verify that `expectedState` still matches the current state before breaking. This avoids destroying a block that was replaced after the collision was captured.

## 8. Material Property Resolution

### 8.1 Property Precedence

Material resolution should use this precedence order:

1. Explicit VS: Kinetic datapack entry for the block or tag.
2. Explicit compatibility entry for a known special block family.
3. Existing VS block data where available.
4. Minecraft block properties, especially blast resistance and friction.
5. Formula-derived fallback values.
6. Conservative global defaults.

The resolver should cache profiles by `BlockState` or by a stable material key. Datapack reload must invalidate the cache.

### 8.2 Blast Resistance as Strength Analog

Minecraft blast resistance is a reasonable starting analog for resistance to sudden impact. It should not be used directly as physical joules. It must be normalized and clamped.

Example normalized conversion:

```text
strength = clamp(
    baseStrength * log2(1 + blastResistance) ^ strengthExponent,
    minimumStrength,
    maximumStrength
)
```

The exact constants must be calibrated with test impacts, not assumed to be realistic SI values. The formula should preserve the useful ordering that wool, glass, wood, stone, obsidian, and indestructible blocks resist damage progressively more strongly.

### 8.3 Toughness Formula

Toughness can be estimated from blast resistance, block volume, and density:

```text
toughness = strength
          * volumeFactor
          * densityFactor
          * toughnessMultiplier
```

For ordinary full blocks, `volumeFactor` is 1. For slabs, panes, stairs, and partial shapes, use the collision or outline volume fraction, bounded so thin blocks do not become accidentally invulnerable.

### 8.4 Density and VS Mass

VS mass is already used to construct ship inertia and total mass. VS: Kinetic should use the same mass information wherever it can resolve the relevant block state. If no VS mass is available, derive a relative density from material families or use a configurable default.

The addon must not independently recalculate total ship mass for collision energy. Use `PhysShip.mass` or the loaded ship's inertia mass. Independent mass totals would diverge from VS2 after ship edits and splits.

### 8.5 Formula-Derived Fallbacks

The fallback resolver should use stable block properties rather than block class names where possible:

- Blast resistance for strength.
- Destroy time or hardness only as a secondary scaling hint, never as the sole value.
- Collision-shape volume for amount of material at the contact.
- `BlockState.isAir`, fluid state, and collision shape emptiness for special handling.
- Vanilla sound type for impact audio classification.

Unknown blocks should default to breakable but conservative behavior, with a warning only in debug mode to avoid log spam.

## 9. Datapack Format

The first format should be simple and tag-friendly. Suggested directory:

```text
data/<namespace>/vskinetic_materials/<file>.json
```

Suggested schema:

```json
[
  {
    "tag": "minecraft:logs",
    "priority": 100,
    "strength_multiplier": 0.8,
    "toughness_multiplier": 0.9,
    "density_multiplier": 1.0,
    "friction_multiplier": 1.0,
    "elasticity_multiplier": 1.0,
    "brittleness": 0.35,
    "penetration_multiplier": 1.0,
    "damage_multiplier": 1.0,
    "treatment": "normal"
  },
  {
    "block": "minecraft:obsidian",
    "priority": 200,
    "strength_multiplier": 8.0,
    "toughness_multiplier": 12.0,
    "brittleness": 0.1,
    "treatment": "protective"
  }
]
```

The format should support `block` and `tag`, with priority rules matching the useful behavior of VS2's mass datapack system. It should also support an optional `state` object later if a block's orientation or contents materially affect damage.

Do not require pack authors to specify every property. Missing values inherit from the formula-derived profile.

Potential future fields:

- `max_damage_per_event`.
- `ignore_protection` for tightly controlled server packs, disabled by default.
- `block_entity_policy`.
- `fragmentation_radius`.
- `can_penetrate`.
- `damage_sound`.

## 10. Collision and Energy Model

### 10.1 Relative Normal Velocity

Only the closing component of velocity should contribute to direct impact energy:

```text
v_normal = dot(relativeVelocity, collisionNormal)
closingSpeed = max(0, abs(v_normal))
```

The normal orientation must be tested in a controlled scene because event conventions can differ. The implementation should normalize the normal and choose the sign that produces a positive closing speed.

Tangential velocity should be retained separately:

```text
v_tangent = relativeVelocity - v_normal * normal
```

This allows glancing impacts to cause frictional scraping without treating all tangential speed as penetration energy.

### 10.2 Effective Mass

For two moving bodies, use reduced mass:

```text
reducedMass = (massA * massB) / (massA + massB)
```

For ground, use a configured effective ground mass or treat the ground as immovable and use the moving ship's effective mass. Avoid literal infinity in calculations.

The first model can use translational reduced mass. A later model may account for rotational contact velocity using each body's angular velocity and contact offset from its center of mass.

### 10.3 Translational Impact Energy

The baseline normal kinetic energy is:

```text
impactEnergy = 0.5 * reducedMass * closingSpeed^2
```

Apply configurable scale factors rather than claiming that Minecraft units are SI units:

```text
usableEnergy = impactEnergy
             * globalEnergyScale
             * contactAreaFactor
             * angleFactor
```

The model must clamp energy to a safe maximum before any multiplication that could overflow or create extreme destruction.

### 10.4 Contact Angle

A direct impact should have more penetration than a glancing impact. Let `faceAlignment` be the absolute dot product between the incoming direction and the surface normal.

```text
angleFactor = clamp(faceAlignment ^ angleExponent, minAngleFactor, 1.0)
```

For glancing contacts, route part of the energy to tangential scrape damage instead of deep penetration.

### 10.5 Contact Area

The collision event may provide contact points but not a complete contact patch. Estimate area by clustering nearby contacts and using the local block-face footprint. The first implementation should use discrete tiers:

- Point contact: 1 block seed.
- Small patch: 2-4 nearby seeds.
- Broad patch: bounded cluster of seeds.

Do not multiply energy by raw contact count without a cap, because solver contact generation can vary with shape complexity.

### 10.6 Rotational Contact Velocity

For an improved model, calculate velocity at a contact point:

```text
contactVelocity = linearVelocity + cross(angularVelocity, contactOffset)
```

Use each ship's center of mass and angular velocity when available. This captures bow/stern impacts and rotating ships more accurately than linear velocity alone.

This should be part of the first physics implementation if the VS2 API exposes the required values cleanly. Otherwise it should be an isolated extension point and added after the basic system is stable.

## 11. Penetration Model

### 11.1 General Approach

The penetration planner receives:

- An impact energy budget.
- An impact position and direction.
- A target ship or terrain body.
- A material resolver.
- A maximum depth, width, and block-operation budget.

It walks a discrete path through the target surface. Each candidate block consumes an energy cost based on the block's profile and the local impact state. If energy is exhausted, the path stops.

### 11.2 Finding the First Block

For ground, transform the world contact position into a block position and search around the contact normal for the first non-air collision block.

For ships:

1. Convert the world contact point into ship coordinates using the ship's `worldToShip` transform.
2. Convert to a local block coordinate.
3. Search a small neighborhood around the contact point to account for numerical separation and partial block shapes.
4. Select blocks whose collision shape intersects the contact region.

The resolver must preserve both local ship position and world block position. The local position is required to query ship blocks correctly; the world position is required for effects and protection checks.

### 11.3 Direction of Travel

The penetration direction should point from the impacting body into the target. For a ship-to-ship event, derive it from the relative velocity and the contact normal, then validate it against the two possible directions.

For each body, evaluate damage independently:

- Body A receives damage along the normal entering A.
- Body B receives damage along the normal entering B.

The energy split should depend on effective mass, elasticity, and material resistance. The simplest stable starting rule is to assign an impact share based on the opposite body's effective resistance and then adjust using restitution.

### 11.4 Block Energy Cost

Suggested baseline:

```text
blockEnergyCost = baseBreakEnergy
                * profile.toughness
                * profile.penetrationMultiplier
                * thicknessFactor
                * shapeFactor
```

`thicknessFactor` is the distance traveled through the block along the penetration ray. `shapeFactor` is based on the amount of collision volume encountered, with minimum and maximum clamps.

The planner should distinguish between:

- **Failure threshold:** minimum local energy required to break the block.
- **Energy cost:** total energy removed from the penetration budget.

This prevents a very weak block from being broken for free and prevents a very strong block from consuming unlimited energy.

### 11.5 Damage Shapes

The initial planner should support three bounded patterns:

- `POINT`: one primary path with minimal lateral damage.
- `CONE`: path width expands with depth, useful for brittle materials and high-energy impacts.
- `SCRAPE`: shallow surface traversal for glancing hits.

The default should be `CONE` only within a small configured radius. Large explosive-looking craters should be a separate opt-in behavior, not an accidental result of high ship speed.

### 11.6 Structural Failure Without Full Structural Simulation

A later layer can add approximate structural failure:

- After blocks are removed, identify detached components through a bounded connectivity scan.
- Ask VS2's normal splitting system to handle actual ship separation where possible.
- Do not implement a second full ship connectivity solver in the MVP.
- Apply a configurable scan budget and defer large scans.

The initial system should rely on VS2's existing block-edge/corner connectivity and split handling rather than duplicate it.

## 12. Ship-to-Ground Damage

Ground damage follows the same energy model but treats terrain as static.

Required behavior:

- Resolve the ground body from the current dimension.
- Find terrain blocks around the contact point.
- Transform only when the target is a ship; ground uses world coordinates directly.
- Respect block break permissions and protection hooks.
- Skip fluids, air, replaceable plants, and non-solid blocks unless configured otherwise.
- Apply a maximum ground damage budget per event and per server tick.

The initial terrain algorithm should avoid scanning a large radius. It should remove a bounded penetration path and a small lateral patch, then leave normal Minecraft updates to handle fluid flow and block neighbors.

## 13. Ship-to-Ship Damage

For ship-to-ship impacts:

1. Confirm both IDs refer to loaded ships or that one is the ground body.
2. Resolve the contact patch.
3. Obtain each ship's mass, linear velocity, angular velocity, transform, and elasticity-related data if exposed.
4. Calculate closing speed and effective mass.
5. Calculate a shared impact energy.
6. Split the energy into body A and body B damage budgets.
7. Plan damage independently in each ship's local coordinates.
8. Merge overlapping operations across contact points.
9. Execute only operations whose blocks still match their expected states.

The ships should not be forcibly merged or moved by the addon. VS2's rigid-body response remains authoritative.

## 14. Block Damage Execution

### 14.1 Queueing

Use a per-server or per-dimension queue of `DamagePlan` objects. The queue should store only compact records and should have a maximum size. If the queue overflows, coalesce impacts by body pair and contact region before dropping lower-priority work.

### 14.2 Server-Thread Processing

Process the queue in a Forge server-level tick event. The processor must:

- Confirm the level and target body still exist.
- Verify the target block is still the expected state.
- Recheck protection permissions.
- Apply a normal Forge/vanilla block-break path where possible.
- Avoid duplicating drops or experience.
- Mark neighbors for updates only when needed.
- Record success, skip, and failure reasons for diagnostics.

### 14.3 Protection Handling

The default protection service should be conservative:

- Check whether the relevant player or server-owned damage source is permitted to modify the block.
- If no player caused the collision, use a server/configuration policy rather than inventing a player identity.
- Provide a platform-neutral `ProtectionService` interface.
- Implement Forge-native behavior first.
- Add optional adapters for claim systems only when their APIs are present.
- Never bypass protections by default.

Because normal block-break events are often player-oriented, the executor may need a dedicated `DamageSource` and an integration-specific permission query. This must be tested against the selected claim mods rather than assumed to work through one vanilla event.

### 14.4 Block Entities

Block entities require explicit policy. The MVP should use these defaults:

- Skip block entities by default.
- Allow datapacks to classify known block entities as breakable.
- Add special integration handlers for Create/CBC/Clockwork only when the target mod is loaded.
- Do not remove a block entity while another mod is in the middle of a tick update.
- Ensure inventory drops and custom removal logic are handled correctly.

## 15. Compatibility Strategy

### 15.1 Create

The addon should treat Create blocks as ordinary blocks initially, using their blast resistance, collision shape, sound, and VS mass data. Create kinetic blocks and block entities should be skipped unless explicitly supported.

Where Create provides a safe removal or invalidation API, a later `CreateIntegration` can invoke it. This integration must be optional and loaded only when Create is present.

### 15.2 Create Big Cannons

CBC blocks should initially use the same generic material resolver. The integration layer should detect CBC and provide policies for:

- Cannon barrels and breeches.
- Projectiles and projectile entities near impact locations.
- Block entities whose removal requires CBC cleanup.

Projectile impacts should remain separate from ship collision impacts. Do not infer projectile damage from a block collision event unless the projectile is explicitly associated with that event.

### 15.3 Clockwork

Clockwork demonstrates the intended VS addon patterns: ship attachments, physics listeners, server-level tick processing, and VS collision events. VS: Kinetic should remain compatible with Clockwork by:

- Avoiding modifications to Clockwork controllers.
- Treating Clockwork blocks through their existing block properties initially.
- Skipping complex Clockwork block entities unless an integration handler is registered.
- Testing bearings, contraptions, and ship attachments separately.

### 15.4 Claims and Protection Mods

Use optional runtime detection and adapter interfaces. A missing integration must not prevent the addon from loading. An integration failure should disable only that adapter and log a concise warning in debug or warning mode.

## 16. Configuration

The server configuration should expose the major policy and performance controls.

Suggested settings:

```text
enabled
ship_to_ship_damage
ship_to_ground_damage
global_energy_scale
minimum_closing_speed
minimum_impact_energy
maximum_impact_energy
maximum_events_per_tick
maximum_contacts_per_event
maximum_blocks_per_event
maximum_blocks_per_tick
maximum_penetration_depth
maximum_damage_radius
enable_rotational_contact_velocity
enable_scrape_damage
enable_lateral_damage
damage_block_entities
damage_fluids
damage_protected_blocks
drop_items
play_effects
debug_logging
debug_rendering
```

Configuration values must be validated and clamped. A server operator should be able to disable terrain damage independently from ship damage.

## 17. Commands and Diagnostics

Add an administrator/debug command namespace, for example `/vskinetic`.

Suggested commands:

```text
/vskinetic status
/vskinetic reload
/vskinetic debug on|off
/vskinetic profile <block>
/vskinetic simulate <speed> <mass> <block>
```

The `profile` command should display resolved material values, their source, and the precedence layer that supplied each value.

Debug impact output should include:

- Body IDs and whether either body is ground.
- Contact position and normal.
- Linear and rotational relative velocity.
- Closing speed.
- Effective mass.
- Raw and usable energy.
- Number of contact clusters.
- Planned block count.
- Skipped blocks and reasons.

Debug rendering can show contact points, normals, penetration paths, and planned damage blocks on the client. It should be disabled by default and must not be required for server operation.

## 18. Event and Thread Safety

The implementation must explicitly document the thread assumptions of the pinned VS2 version.

Rules:

- Treat collision callbacks as read-only capture points.
- Copy all mutable vectors immediately.
- Use a concurrent queue for crossing from physics callbacks to the server tick if required.
- Never call `Level.setBlock`, block entity removal, drops, or Forge event dispatch from the capture callback.
- Aggregate and plan impacts on the server thread unless profiling proves a safe alternative.
- Do not hold world, ship, block state, or event references in long-lived queues.
- Revalidate every block operation before execution.

## 19. Performance Model

The primary performance risk is not the collision event itself; it is block scanning and block mutation after a large impact.

The implementation must enforce all of the following:

- One global maximum number of captured events per physics tick.
- One maximum contact count per event.
- One maximum number of spatial contact clusters per event.
- One maximum number of planned block operations per event.
- One maximum number of executed block operations per server tick.
- A maximum local search radius around each contact.
- No unbounded flood fill in the MVP.
- Caches for material profiles and repeated collision-shape queries.
- Coalescing of duplicate block operations.
- Metrics for queue size, planning time, execution time, and skipped operations.

When a limit is reached, stop planning additional damage and record the reason. The result should be a smaller crater, not a server stall.

## 20. Testing Strategy

### 20.1 Unit Tests

Create deterministic tests for:

- Normal and tangential velocity decomposition.
- Relative normal velocity sign handling.
- Reduced mass calculations.
- Ground-body effective mass behavior.
- Blast-resistance normalization and clamping.
- Material precedence and datapack overrides.
- Partial-block shape factors.
- Energy cost and penetration depth.
- Brittleness and lateral damage limits.
- Contact clustering and duplicate removal.
- Configuration validation.

### 20.2 Game Tests

Build controlled worlds with:

- A static ground test wall.
- A small wooden ship.
- A small stone ship.
- A small iron ship.
- Direct head-on impacts.
- Glancing impacts.
- Low-speed contacts below the threshold.
- Static-versus-moving collisions.
- Two ships with different masses.
- Rotating ships striking a target.
- Ship-to-ground impacts at different angles.

Record expected ranges rather than exact block counts initially. Exact outcomes can vary with collision shape and solver contact generation.

### 20.3 Compatibility Tests

Test with:

- VS2 alone.
- VS2 plus Create.
- VS2 plus Create Big Cannons.
- VS2 plus Clockwork.
- VS2 plus Create, CBC, and Clockwork together.
- A protection/claim mod selected for the target modpack.
- Block entities at the impact location.
- Chunk boundaries and partially loaded areas.

### 20.4 Regression Tests

Every fixed bug involving a specific block, mod, orientation, or collision geometry should become a reproducible test or fixture. Keep test worlds and datapacks versioned with the project.

## 21. Implementation Phases

### Phase 0: Project Bootstrap

Deliverables:

- Kotlin multiloader Gradle scaffold.
- Forge 1.20.1 run configuration.
- VS2 dependency resolution.
- Minimal mod metadata and initialization.
- A version compatibility check shown in logs.
- Empty Fabric module or clearly reserved loader boundary.

Exit criteria:

- The dev client/server starts with VS2.
- The addon loads without client-only class references on the server.
- A basic server command or log confirms initialization.

### Phase 1: Collision Telemetry

Deliverables:

- VS collision event registration.
- Immutable `ImpactRecord`.
- Ground-body identification.
- Safe concurrent queue.
- Server tick drain.
- `/vskinetic status` and debug logging.

Exit criteria:

- Ship-to-ground and ship-to-ship contacts are observed.
- Contact positions and normals can be visualized or logged.
- No blocks are modified.
- No event callback causes world mutation.

### Phase 2: Material Resolver

Deliverables:

- `MaterialProfile`.
- Blast-resistance-based defaults.
- VS mass/friction/elasticity reuse.
- Formula-derived fallbacks.
- Datapack loader with block/tag priority.
- `/vskinetic profile` command.

Exit criteria:

- Common vanilla blocks resolve stable profiles.
- Unknown blocks resolve safely.
- Datapack reload changes profiles without restart.
- Profile source is visible in debug output.

### Phase 3: Energy Calculation

Deliverables:

- Relative normal and tangential velocity.
- Reduced mass calculation.
- Ground effective-mass handling.
- Contact angle factor.
- Contact area tiers.
- Configurable energy scaling and thresholds.

Exit criteria:

- Low-speed impacts do not damage blocks.
- Direct impacts produce more energy than glancing impacts at equal speed.
- Heavier ships produce more damage at equal velocity.
- Calculations remain finite under extreme configured values.

### Phase 4: Read-Only Penetration Planner

Deliverables:

- Ship/local and ground/world coordinate resolvers.
- First-block contact search.
- Penetration path generation.
- Material energy costs.
- Bounded point, cone, and scrape patterns.
- `DamagePlan` output without execution.

Exit criteria:

- Debug output shows plausible planned paths.
- Ship and ground coordinates are correct for rotated ships.
- Duplicate contacts do not multiply damage uncontrollably.
- Planning respects all configured limits.

### Phase 5: Safe Block Damage Execution

Deliverables:

- Server-thread damage queue.
- Expected-state revalidation.
- Protection service.
- Normal block-break/drop behavior.
- Block entity skip policy.
- Neighbor update policy.
- Damage results and statistics.

Exit criteria:

- Valid unprotected blocks are broken as planned.
- Protected blocks are skipped.
- Replaced blocks are not accidentally destroyed.
- Ship mass/splitting updates correctly after damage.
- No duplicate drops occur.

### Phase 6: Both-Side Ship Damage and Effects

Deliverables:

- Energy sharing between bodies.
- Damage to both ships.
- Rotational contact velocity if available.
- Impact sounds and particles.
- Configurable effect throttling.

Exit criteria:

- Head-on impacts damage both appropriate hulls.
- Mass and material differences visibly affect outcomes.
- Effects do not cause network or server spam.

### Phase 7: Compatibility Layer

Deliverables:

- Generic Create handling.
- CBC block/entity policies.
- Clockwork block/entity policies.
- Claim adapter interface and first tested adapter.
- Compatibility test matrix.

Exit criteria:

- The addon loads with each integration absent or present.
- Unsupported block entities fail closed.
- Known integrations do not produce duplicate updates or crashes.

### Phase 8: Structural Approximation and Polish

Deliverables:

- Optional brittle splash damage.
- Optional bounded lateral damage.
- Better connectivity/splitting coordination.
- Impact sensor API or ComputerCraft telemetry if desired.
- Configuration documentation.
- Modpack-facing balance presets.

Exit criteria:

- Large impacts remain bounded.
- Structural failure feels consistent without requiring a custom solver.
- Server operators can tune the system without code changes.

## 22. API Boundaries

The following interfaces should be kept narrow and stable:

```kotlin
interface MaterialResolver {
    fun resolve(level: Level, pos: BlockPos, state: BlockState): MaterialProfile
}

interface ProtectionService {
    fun canDamage(level: ServerLevel, pos: BlockPos, context: DamageContext): Boolean
}

interface CollisionTargetResolver {
    fun resolveBlock(level: ServerLevel, target: CollisionTarget, worldPosition: Vector3d): BlockCandidate?
}

interface DamagePlanner {
    fun plan(level: ServerLevel, impact: AggregatedImpact): DamagePlan
}

interface DamageExecutor {
    fun execute(level: ServerLevel, plan: DamagePlan): DamageResult
}
```

Platform and compatibility implementations should not leak through the physics model. For example, `ImpactEnergyModel` should not know whether a protection decision came from Forge, a claim mod, or a server policy.

## 23. Risks and Mitigations

### Risk: VS API Changes

Mitigation: isolate VS access in `VsApiFacade`, pin versions, add startup compatibility checks, and maintain a small compatibility matrix.

### Risk: Collision Events Arrive After Significant Overlap

Mitigation: use bounded penetration allowances, contact normal plus relative velocity, and conservative maximum depth. Do not attempt unbounded retroactive reconstruction.

### Risk: Too Many Contact Points

Mitigation: spatial clustering, body-pair aggregation, duplicate operation removal, and hard operation limits.

### Risk: Protection API Incompatibility

Mitigation: fail closed, use optional adapters, and test the actual target claim mods. The default behavior should avoid modifying blocks when permission cannot be established in protected contexts.

### Risk: Block Entity Corruption

Mitigation: skip block entities in the MVP, use explicit compatibility handlers, and revalidate state immediately before mutation.

### Risk: Unexpected Ship Splitting

Mitigation: use VS2's existing split mechanism and avoid a parallel connectivity solver until the base damage system is proven.

### Risk: Unrealistic Material Balance

Mitigation: expose all important scaling constants, provide a simulation command, store test scenarios, and tune using relative outcomes rather than claimed real-world units.

### Risk: Server Tick Lag

Mitigation: cap every queue and scan, process over multiple ticks when necessary, cache material data, and expose profiling metrics.

## 24. Definition of Done for the First Playable Release

The first playable release is complete when all of the following are true:

- It runs on Forge 1.20.1 with the pinned VS2 version.
- It detects ship-to-ground and ship-to-ship collision contacts.
- It computes bounded energy from mass and closing velocity.
- It uses blast resistance as the default strength analog.
- It reuses VS mass/friction/elasticity data where available.
- It supports datapack overrides and tags.
- It plans directional penetration through block layers.
- It damages terrain and ships independently through configuration.
- It honors tested protection behavior.
- It skips unsupported block entities safely.
- It updates the world only on the server thread.
- It prevents duplicate operations and excessive per-tick work.
- It has deterministic unit tests for the math and game tests for collision scenarios.
- It includes debug commands sufficient to tune a modpack.
- It documents known incompatibilities and configuration limits.

## 25. Recommended First Coding Task

The first implementation task should be **Phase 0 followed by Phase 1 only**:

1. Create the Kotlin multiloader Forge scaffold.
2. Pin VS2 and verify a dev server starts.
3. Register `vsApi.collisionStartEvent`.
4. Capture and log normalized contact records.
5. Add a debug command or renderer for contact points and normals.
6. Test four cases: ship-to-ground, ship-to-ship, low-speed contact, and a rotated ship.

Do not implement block destruction before collision telemetry is proven. Correct coordinate transforms and event timing are the foundation for every later subsystem.

## 26. Open Decisions Before Implementation

These values should be selected during the first prototype rather than guessed in the final balance model:

- The exact VS2 2.4.x artifact and VS Core version. The reference source currently identifies VS2 2.4.7 with VS Core `1.1.0+b714ef303e`; these should be pinned together rather than selected independently.
- Whether the first Forge build uses only common plus Forge or includes an empty Fabric module immediately.
- The energy scale and conversion constants.
- The minimum closing-speed threshold.
- The maximum default blocks destroyed per event.
- The default treatment of block entities.
- The claim/protection mod or mods used by the target modpack.
- Whether damage should produce drops by default.
- Whether a collision with protected terrain should still damage the ship without damaging terrain.
- Whether fluids should absorb energy, be ignored, or receive a separate fluid-damage model.
- Whether direct collision damage should affect living entities in the first release.

These should be recorded as configuration or design decisions after controlled tests, not buried as unexplained constants in code.
