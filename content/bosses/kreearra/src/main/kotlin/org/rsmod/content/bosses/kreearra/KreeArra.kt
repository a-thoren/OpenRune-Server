package org.rsmod.content.bosses.kreearra

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import org.rsmod.api.bosses.dsl.*
import org.rsmod.api.bosses.runtime.BossCombat
import org.rsmod.api.bosses.runtime.BossDeps
import org.rsmod.api.bosses.runtime.BossPluginScript
import org.rsmod.api.bosses.spec.Effect
import org.rsmod.api.script.onEvent
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.npc.NpcStateEvents
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.ScriptContext

class KreeArra @Inject constructor(deps: BossDeps) : BossPluginScript(deps) {

    private val avatarId by lazy { AVATAR.asRSCM(RSCMType.NPC) }
    private val bodyguardIds by lazy { BODYGUARDS.map { it.asRSCM(RSCMType.NPC) }.toHashSet() }

    override fun ScriptContext.startup() {
        BossCombat.register(this, spec, deps)

        // When Kree'arra respawns, any of his bodyguards that are currently dead are respawned
        // alongside him. Bodyguards that are still alive keep their own state.
        onEvent<NpcStateEvents.Respawn> { if (npc.id == avatarId) respawnDeadBodyguards(npc) }
    }

    private fun respawnDeadBodyguards(avatar: Npc) {
        deps.npcRepo
            .findAll(ZoneKey.from(avatar.coords), zoneRadius = BODYGUARD_SEARCH_RADIUS)
            .filter { it.id in bodyguardIds && it.hitpoints == 0 }
            .forEach { bodyguard ->
                // Bring the respawn forward to next cycle; the engine's reveal pass then runs the
                // normal respawn path (stats restored, coords reset to spawn) for it.
                bodyguard.lifecycleRespawnCycle = deps.mapClock.cycle + 1
            }
    }

    override val spec =
        boss(AVATAR) {
            // Kree'arra is fast (3-tick attacks) and attacks primarily from range.
            stats(attackRate = 3, aggressionRadius = 8)

            // Signature wind blast: a Ranged AoE that strikes every player in the eyrie, not just
            // the current target. A projectile is fired at each player from the avatar.
            val windRanged =
                ability("wind_ranged") {
                    anim("seq.godwars_armadyl_avatar_wind_attack")
                    include(
                        onEach(
                            AllInRadius(radius = ROOM_RADIUS),
                            Effect.Projectile(
                                spotanim = "spotanim.godwars_armadyl_avatar_wind_attack_spotanim",
                                hit = Effect.Hit(damage = Roll(0..69), type = Ranged),
                            ),
                        )
                    )
                }

            // Single-target magic attack.
            val magic =
                ability("magic") {
                    anim("seq.godwars_armadyl_avatar_wind_attack")
                    projectile(
                        spotanim = "spotanim.godwars_armadyl_avatar_magic_attack_spotanim",
                        hit = Effect.Hit(damage = Roll(0..21), type = Magic),
                    )
                }

            // Talon swipe when a player is standing next to him.
            val claw =
                ability("claw") {
                    anim("seq.godwars_armadyl_avatar_claw_attack")
                    hit {
                        damage(0..25).roll()
                        type(Melee)
                    }
                }

            phase("combat") {
                weightedSelectorRandom {
                    +random(windRanged, weight = 6)
                    +random(magic, weight = 2)
                    +random(claw, weight = 2, requires = WithinMeleeRange)
                }
            }
        }

    private companion object {
        private const val AVATAR = "npc.godwars_armadyl_avatar"
        private val BODYGUARDS =
            listOf(
                "npc.godwars_armadyl_bodyguard_geerin",
                "npc.godwars_armadyl_bodyguard_kilisa",
                "npc.godwars_armadyl_bodyguard_skree",
            )
        private const val BODYGUARD_SEARCH_RADIUS = 10

        // Chebyshev radius from the avatar that comfortably covers the whole boss room, so the wind
        // blast hits every player inside it (players in other regions/instances are far away and
        // excluded).
        private const val ROOM_RADIUS = 15
    }
}
