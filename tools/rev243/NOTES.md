# Rev 233 -> 243 upgrade notes (branch feat/rev-243)

Written without a gradle build: everything here was checked offline against cache 2777
(OpenRS2, 2026-11-25) and the rsprot `osrs-243` 1.0.0-ALPHA-20261126 jars. Expect several
compile-fix and boot-fix rounds in the build window.

## Build window procedure
1. Live server stopped, Ryan logged out.
2. `scripts/cutover-243.sh prepare` (stop, back up `.data`, install cache 2777 + empty xteas,
   force re-pack, add the "RSMod 243" RSProx target).
3. Compile: `./gradlew --no-daemon compileKotlin`, fix errors, repeat.
4. `./gradlew packCache`, then boot. The **type verifier output is the main test** (compile and
   packCache do not catch reference-hash mismatches):
   - save the log, run `python -I tools/rev243/fixhashes.py <log> .`, reboot; repeat until clean.
   - any "reference not found" names: re-run `tools/rev243/unresolved.py` and `refcheck.py`.
5. Log in through "RSMod 243" (no RuneLite pin: 243 is live, so current RuneLite works).
6. Merge to main only after an end-to-end login. Rollback: `scripts/cutover-243.sh rollback`.

## What changed (and how it was verified)
- Config codecs (`tools/cache-check/opwalk.py` parses every record of cache 2777 with 0 errors):
  - obj: int model opcodes 44-54; client-only 15/99/160/161/251 kept verbatim (`RawOpcodes`).
  - loc: int models 6/7; multiloc 97/98 (24-bit ids); client-only 91/93-96/100-102 kept.
  - npc: int models 61/62; client-only 111/126/129/130/145-147/251-253 kept.
  - spotanim: int model 3, flags 10/42. param: 7 (long default), 8 (var type) kept.
  - Encoders always emit the new int/24-bit opcodes and append the kept raw records.
- Interface components: model ids are ints. Clientscripts: 16-byte trailer (verified on
  565/599 sampled scripts), long-constant opcode 61 read and dropped (server never runs cs2).
- Params: value type 2 = long (none used by 2777 yet).
- Map archive: numeric groups (group = map square id, file 0 terrain, file 1 locs), no xtea;
  server-only n/o/a groups at id + 65536 (`MapArchive`).
- rsprot 243 (`tools/rev243/rsprot-233-vs-243-api-diff.txt`): rebuild messages have no xtea;
  OpLoc V2/V3, OpLocT V1/V2, OpLoc6 V1/V2 all routed to shared handlers; network layer moved
  to `InfoProtocols`/`Infos` (root world only); camera messages take `heightRelative = true`.
  Note: `If3Button` still arrives (IfButtonXDecoder produces it).
- Symbols regenerated with `gensyms.py` + `mergesyms.py` (server-only ids kept; the tools
  reproduce the 233 files exactly when run 233 -> 233).

## Content removed/adjusted because 243 changed it
- Lumbridge tutors removed from the cache: Arthur the Clue Hunter, prayer tutor, smithing
  apprentice, woodsman tutor, fishing tutor, banker tutor (scripts/refs deleted). Hatius ->
  `hatius_cosaintus`.
- Bank: Swap/Insert is one toggle, Note is a toggle. Shop subtext component removed.
  Side-panel music unlock toggle removed. Music tab buttons: Area / Shuffle / Single.
- Chat anims: evil anims removed -> neutral fallbacks (`chatneu_idle`, `chatlaugh1`).
- Level-up display: `ranged`/`runecraft` renames; hunter and sailing icons now exist.

## Disabled on this branch (port after cutover, in this order)
1. **Skill guides** -> `skill_guide_v2` (varp `skill_guide_v2`, interface 860).
2. **Canoes** -> per-station `canoe_map_*` interfaces.
Re-enable by removing them from `disabledProjects` in `settings.gradle.kts`.

## Known risks / guesses to verify in game
- Network layer port (Infos API) is the least certain piece; watch player/npc info packets.
- Camera `heightRelative = true` is a guess at the old ground-relative behaviour.
- New server-built params (not from the cache) are encoded without opcode 8.
- Music tab mode mapping (Single/Shuffle) and the `musicplay` values may have changed.
- Unnamed-base multiloc furnaces/anvils are not bound by the label scan.
