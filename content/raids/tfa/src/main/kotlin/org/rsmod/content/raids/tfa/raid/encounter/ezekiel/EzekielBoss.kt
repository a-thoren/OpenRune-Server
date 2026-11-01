package org.rsmod.content.raids.tfa.raid.encounter.ezekiel

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import org.rsmod.api.bosses.dsl.*
import org.rsmod.api.bosses.runtime.BossCombat
import org.rsmod.api.bosses.runtime.BossDeps
import org.rsmod.api.bosses.runtime.BossPluginScript
import org.rsmod.api.bosses.spec.BossSpec
import org.rsmod.api.bosses.spec.Condition
import org.rsmod.api.bosses.spec.Effect
import org.rsmod.api.bosses.spec.VarExpr
import org.rsmod.api.player.isValidTarget
import org.rsmod.api.script.onNpcHit
import org.rsmod.content.raids.tfa.raid.encounter.ezekiel.EzekielVarns.DEAD
import org.rsmod.content.raids.tfa.raid.encounter.ezekiel.EzekielVarns.DONE
import org.rsmod.content.raids.tfa.raid.encounter.ezekiel.EzekielVarns.FINALE
import org.rsmod.content.raids.tfa.raid.encounter.ezekiel.EzekielVarns.HP_MAX
import org.rsmod.content.raids.tfa.raid.encounter.ezekiel.EzekielVarns.WEAKENED
import org.rsmod.game.entity.npc.NpcApproach
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Ezekiel (The Fractured Archive, west wing), ported from tsps `Ezekiel.FracturedArchive.js`. The
 * spec runs his 36-37 tick cycle: the special (star + swords) at its start, melee at +7, +12, +26
 * and +31, the swords flying at +14 (in [EzekielFight]). Below a third he walks to his finale tile
 * and the fight's barrage takes over until he falls.
 */
class EzekielBoss @Inject constructor(deps: BossDeps, private val fights: EzekielEncounter) :
    BossPluginScript(deps) {

    override fun ScriptContext.startup() {
        BossCombat.register(this, spec, deps, onHit = { fights.of(npc)?.updateBar() })
        val sword = ServerCacheManager.getNpc(SWORD.asRSCM(RSCMType.NPC))
        if (sword != null) {
            onNpcHit(sword) { fights.ofSword(npc)?.chargeSword(npc, hit.damage) }
        }
    }

    override val spec: BossSpec =
        boss(BOSS) {
            stats(attackRate = 1)

            val arena = EzekielArena.AREA

            fun fighting(): Condition = !varnIs(DONE, 1) and !varnIs(DEAD, 1) and !varnIs(FINALE, 1)

            // ---- melee cleave, or magic at everyone ---------------------------------------------

            val tankInReach =
                Condition.Custom { npc, target ->
                    target != null &&
                        target.isValidTarget() &&
                        EzekielArena.inArena(npc.spawnCoords, target.coords) &&
                        EzekielArena.footprintDistance(npc.coords, target.coords) <= 1
                }

            val cleave =
                withTiles(
                    CLEAVE,
                    customTiles(arena) { npc, target, _ ->
                        EzekielArena.cleaveTiles(npc.coords, target.coords)
                    },
                    sequence(
                        faceTarget(),
                        anim("seq.npc_ezekiel_melee01"),
                        onTiles(
                            bound(CLEAVE),
                            sequence(
                                mapSpotanim(
                                    "spotanim.tfa_wave_dust",
                                    CurrentTile,
                                    delay = DUST_DELAY,
                                ),
                                onEach(
                                    playersOn(CurrentTile),
                                    hit {
                                        damage(0..EzekielArena.MELEE_MAX).roll()
                                        type(Melee)
                                        penetration(EzekielArena.MELEE_PRAYER_LEAK)
                                    },
                                ),
                            ),
                        ),
                    ),
                )

            val magic =
                sequence(
                    anim("seq.npc_ezekiel_magic01"),
                    onEach(
                        playersIn(arena),
                        sequence(
                            spotanim("spotanim.ezekiel_magic_attack_vfx01", target = CurrentTarget),
                            hit {
                                damage(0..EzekielArena.MAGIC_MAX).roll()
                                type(Magic)
                                spotanim("spotanim.ezekiel_magic_attack_impact_vfx01")
                            },
                        ),
                    ),
                )

            ability(
                "melee",
                whenever(fighting() and !varnIs(WEAKENED, 1), whenever(tankInReach, cleave, magic)),
            )

            // ---- the special: the star and the swords -------------------------------------------

            fun starStep(step: Int): Effect =
                onTiles(
                    customTiles(arena) { npc, _, _ -> EzekielArena.starTiles(npc.coords, step) },
                    sequence(
                        mapSpotanim(
                            "spotanim.vfx_ezekiel_star_explosion_spawn01",
                            CurrentTile,
                            delay = STAR_DELAYS[0] + STAR_STEP_CYCLES * (step - 1),
                        ),
                        mapSpotanim(
                            "spotanim.vfx_ezekiel_star_explosion_marker01",
                            CurrentTile,
                            delay = STAR_DELAYS[1] + STAR_STEP_CYCLES * (step - 1),
                        ),
                        mapSpotanim(
                            "spotanim.vfx_ezekiel_star_explosion01",
                            CurrentTile,
                            delay = STAR_DELAYS[2] + STAR_STEP_CYCLES * (step - 1),
                        ),
                    ),
                )

            val starHits =
                onTiles(
                    customTiles(arena) { npc, _, _ -> EzekielArena.starTiles(npc.coords) },
                    onEach(
                        playersOn(CurrentTile),
                        hit {
                            damage(EzekielArena.STAR_MIN..EzekielArena.STAR_MAX).roll()
                            type(Typeless)
                            hazard()
                        },
                    ),
                )

            ability(
                "special",
                whenever(
                    fighting(),
                    sequence(
                        lockMovement(),
                        anim("seq.npc_ezekiel_special01"),
                        spotanim("spotanim.ezekiel_special_crack_vfx01", slot = 2),
                        sound("synth.tfa_ezekiel_special_cast", radius = SOUND_RADIUS),
                        sound("synth.tfa_ezekiel_star_burst", radius = SOUND_RADIUS, delay = 90),
                        starStep(1),
                        starStep(2),
                        starStep(3),
                        external("ezekiel.swords"),
                        after(STAR_HIT_TICKS, whenever(fighting(), starHits)),
                        after(
                            STAR_LOCK_TICKS,
                            whenever(fighting() and !varnIs(WEAKENED, 1), unlockMovement()),
                        ),
                    ),
                ),
            )

            ability(
                "cycle",
                sequence(
                    run("special"),
                    *MELEES.map { after(it, run("melee")) }.toTypedArray(),
                    oneOf(nextAttackIn(CYCLE), nextAttackIn(CYCLE + 1)),
                ),
            )

            // ---- swords through him, and the weakened stance ------------------------------------

            ability(
                "sword_strike",
                whenever(
                    fighting(),
                    sequence(
                        damageSelf(VarExpr.Const(EzekielArena.SWORD_ON_HIM)),
                        external("ezekiel.bar"),
                    ),
                ),
            )

            ability(
                "weaken",
                whenever(
                    fighting(),
                    sequence(
                        setVarn(WEAKENED, 1),
                        lockMovement(),
                        say("Agh!"),
                        anim("seq.npc_ezekiel_idle_to_weaken01"),
                        idleAnim("seq.npc_ezekiel_weaken01"),
                        after(
                            WEAKEN_TICKS,
                            sequence(
                                setVarn(WEAKENED, 0),
                                whenever(
                                    fighting(),
                                    sequence(
                                        clearIdleAnim(),
                                        anim("seq.npc_ezekiel_weaken_to_idle01"),
                                        unlockMovement(),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )

            // ---- start, finale, death -----------------------------------------------------------

            ability(
                "begin",
                sequence(
                    song(MUSIC, arena),
                    setHp(varn(HP_MAX), varn(HP_MAX)),
                    setVarn(WEAKENED, 0),
                    setVarn(FINALE, 0),
                    setVarn(DEAD, 0),
                    setFocus(CurrentTarget),
                    say("To stand against good is to be evil, regardless of reason."),
                    nextAttackIn(FIRST_SPECIAL),
                    external("ezekiel.bar"),
                ),
            )

            val finaleTile = spawnTile(EzekielArena.FINALE_DX, EzekielArena.FINALE_DZ)
            val penFacing = spawnTile(EzekielArena.PEN_NE_DX, EzekielArena.PEN_SW_DZ + 1)

            ability(
                "finale",
                sequence(
                    setVarn(FINALE, 1),
                    setVarn(WEAKENED, 0),
                    transitionTo(FINALE_PHASE),
                    clearIdleAnim(),
                    external("ezekiel.finale_clear"),
                    anim("seq.npc_ezekiel_walk01"),
                    exactMove(finaleTile, FINALE_MOVE_CYCLES),
                    after(
                        FINALE_SETUP_TICKS,
                        whenever(
                            !varnIs(DEAD, 1),
                            sequence(
                                faceTile(penFacing, instant = true),
                                anim("seq.npc_ezekiel_finale01"),
                                spotanim("spotanim.vfx_ezekiel_finale_cast_glow01", slot = 1),
                                idleAnim("seq.npc_ezekiel_finale_loop01"),
                                after(
                                    2,
                                    spotanim(
                                        "spotanim.vfx_ezekiel_finale_cast_glow_loop01",
                                        slot = 5,
                                    ),
                                ),
                                sound("synth.tfa_ezekiel_fence_impact", radius = SOUND_RADIUS),
                                external("ezekiel.finale_setup"),
                            ),
                        ),
                    ),
                ),
            )

            ability(
                "die",
                sequence(
                    interrupt(),
                    retire(),
                    setVarn(DEAD, 1),
                    transitionTo(DEAD_PHASE),
                    clearIdleAnim(),
                    whenever(
                        varnIs(FINALE, 1),
                        anim("seq.npc_ezekiel_finale_death01"),
                        anim("seq.npc_ezekiel_death01"),
                    ),
                    say("...Formidable."),
                    transparency(FADE_START, FADE_END, 0, TRANSPARENT),
                    external("ezekiel.death"),
                    after(DEATH_TICKS, run("complete"), requireAlive = false),
                ),
            )

            ability(
                "complete",
                sequence(setVarn(DONE, 1), external("ezekiel.complete"), despawnSelf()),
            )

            phase(FIGHT, approach = NpcApproach.ChaseToMelee) {
                weightedSelectorRandom { +random("cycle", weight = 1) }
            }
            phase(FINALE_PHASE, scriptedMovement = true, lockMovement = true) {}
            phase(DEAD_PHASE, scriptedMovement = true, lockMovement = true) {}

            every(
                1,
                whenever(
                    Condition.HpBelow(FINALE_FRACTION) and
                        varnAtLeast(HP_MAX, 1) and
                        !varnIs(FINALE, 1) and
                        !varnIs(DEAD, 1),
                    run("finale"),
                ),
            )
            every(1, whenever(Condition.HpExact(1) and !varnIs(DEAD, 1), run("die")))
        }

    private companion object {
        const val BOSS = "npc.tfa_ezekiel"
        const val SWORD = "npc.tfa_ezekiel_sword"
        const val CLEAVE = "cleave"

        const val FIGHT = "fight"
        const val FINALE_PHASE = "finale"
        const val DEAD_PHASE = "dead"

        const val MUSIC = 890
        const val SOUND_RADIUS = 25

        const val FIRST_SPECIAL = 6
        const val CYCLE = 36
        val MELEES = intArrayOf(7, 12, 26, 31)
        const val DUST_DELAY = 5
        val STAR_DELAYS = intArrayOf(34, 64, 94)
        const val STAR_STEP_CYCLES = 2
        const val STAR_HIT_TICKS = 3
        const val STAR_LOCK_TICKS = 4
        const val WEAKEN_TICKS = 8

        const val FINALE_FRACTION = 1.0 / 3
        const val FINALE_MOVE_CYCLES = 60
        const val FINALE_SETUP_TICKS = 3

        const val FADE_START = 210
        const val FADE_END = 270
        const val TRANSPARENT = 255
        const val DEATH_TICKS = 10
    }
}
