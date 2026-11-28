# Sailing tier 1 spike: a stationary, boardable raft (single player)

Scope (Ryan): **one player, one boat**. No other players' boats, no crewing, no per-observer
world-entity sync, no shared-boat visibility. NPC ships are an optional cosmetic layer later and
never block tier 1/2.

Goal of the spike: walk up the Port Sarim gangplank, stand on your raft (a world entity moored at
the dock), see the Sailing side panel, walk around the deck, and walk back off. No steering yet
(that is tier 2: `SetHeading`, moving the avatar).

## What already exists (verified)

- rsprot `osrs-243` 1.0.0-ALPHA-20261126: `WorldEntityAvatarFactory.alloc(...)` (14/15 args:
  index, id, ownerIndex, sizeX/Z in zones, south-west zone x/z of the template, min/max level, fine
  x/z pivot in the root world, projected level, active level, angle 0-2047),
  `WorldEntityAvatar.updateCoord/updateAngle`, `RebuildWorldEntityV4(baseX, baseZ, ?, ?, zoneProvider)`,
  `SetActiveWorldV2`, `InfoPackets.activeWorlds` / `removedWorldIndices` / `added`, incoming
  `OpWorldEntity*` and `SetHeading(heading)`.
- README procedure: root world packets, then for each active world `activeWorld` ->
  (`RebuildWorldEntity` if `added`) -> npc info -> that world's zone updates, then root
  `activeWorld` again.
- Engine: dynamic regions (`Region`/`RegionRegistry`, `RebuildRegion` with a zone provider) used by
  the POH and canoe cutscenes - the template for a boat's map.
- Cache: `worldentity_1` = raft (`mainlevel=1`, bounds 128x384 fine units = 1x3 tiles,
  idle anim `sailing_worldentity_boat_kandarin_1x3_idle_01`, minimap icon `minimap_boat_raft`);
  boarding varbits (`sailing_player_is_on_player_boat`, `sailing_boarded_boat_world`,
  `sailing_boarded_boat(_type)`, `sailing_sidepanel_visible`), gangplank locs
  (`sailing_port_sarim_shipplank_on/off`), interface 937 `sailing_sidepanel`.

## Single-player simplifications

- Exactly one `WorldEntityAvatar`, allocated when the player boards (or logs in on board) and
  released on disembark/logout. Index 1, owner = the player's index.
- `RspCycle.flush`: root world as today, plus at most one active world - the player's raft. No
  loop over other players' worlds, no observer filtering, no NPC ships.
- Zone updates for the boat world: the raft template has no dynamic objs/locs in tier 1, so the
  per-world `sendDynamicZoneUpdates` can be a no-op (send nothing) until cargo/facilities exist.
- Collision: the player walks inside the template region only; the root world's collision is
  untouched (the moored raft doesn't block anyone).

## Steps

1. **Probe (no content):** a `::raft` dev command that allocates the avatar at Port Sarim's dock
   with a hand-made 1x3 template region (`Region` copy of the boat template squares), sends
   `RebuildWorldEntityV4` on `added`, and flushes the one world. Pass = the raft renders in the
   client at the dock. *Unknowns answered here:* (a) which map squares hold the raft template
   (guess from the cache hotspot coord `1_60_100_*`: map square 60,100 level 1 - verify by
   dumping that square's locs), (b) the two unnamed `RebuildWorldEntityV4` ints.
2. **Board/disembark:** gangplank op -> move the player into the boat world (player coords in
   the template, `SetActiveWorld` + boarding varbits + side panel 937), gangplank on deck ->
   back to the dock tile. Pass = standing/walking on deck, side panel shown. *Unknown:* whether
   `MoveGameClick` on deck arrives in template coordinates (log it in step 1).
3. **Persistence:** `sailing_boarded_boat*` and the raft's dock (`sailing_boat_1_port`) are
   saved varbits; on login while boarded, rebuild the avatar before the first flush.
4. **Tier 2 (separate PRs):** `SetHeading` -> angle + move the avatar along the heading each tick
   with sea collision (reject tiles that aren't water / are blocked), buoy docking, then trim XP
   and courier cargo holds.

## Estimate (single-player)

- Steps 1-3: ~1 PR (~600-900 lines: avatar lifecycle, flush branch, region template, gangplank
  and side-panel content) plus **2-3 build-and-login rounds**, mostly for the step 1 unknowns.
- Tier 2 steering + docking: ~1-2 PRs after that.
- Risks: template map squares and zone-provider shape (step 1), deck-click coordinate space
  (step 2), client behaviour of `sailing_transmit_is_at_sea` / `content_restrict_sailing_serverside`.
