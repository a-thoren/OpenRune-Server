package org.rsmod.content.raids.tfa.raid.encounter.ezekiel

import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.rsmod.api.bossbar.plugin.BossHpBarScript
import org.rsmod.api.bosses.runtime.BossDeps
import org.rsmod.api.instances.InstanceAccess
import org.rsmod.api.instances.InstanceArea
import org.rsmod.api.instances.InstanceId
import org.rsmod.api.instances.InstanceManager
import org.rsmod.api.instances.InstanceSpec
import org.rsmod.api.instances.RegionLocal
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.content.raids.tfa.raid.reward.TfaVault
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.map.CoordGrid

@Singleton
class EzekielEncounter
@Inject
constructor(
    private val deps: BossDeps,
    private val manager: InstanceManager,
    private val interactions: AiPlayerInteractions,
    private val hpBar: BossHpBarScript,
    private val events: EventBus,
) {
    private val fights = HashMap<InstanceId, EzekielFight>()

    sealed interface Opened {
        class Room internal constructor(internal val fight: EzekielFight, val enter: CoordGrid) :
            Opened

        data class Failed(val reason: String) : Opened
    }

    internal fun of(npc: Npc): EzekielFight? = fights.values.firstOrNull { it.boss === npc }

    internal fun ofSword(npc: Npc): EzekielFight? = fights.values.firstOrNull { it.isSword(npc) }

    internal fun of(id: InstanceId): EzekielFight? = fights[id]

    internal fun at(tile: CoordGrid): EzekielFight? =
        fights.values.firstOrNull { it.contains(tile) }

    internal fun open(owner: Player): Opened =
        when (
            val result =
                manager.create(owner, KEY, SPEC, InstanceAccess.Private, deps.mapClock.cycle)
        ) {
            is InstanceManager.Result.Failed -> Opened.Failed(result.reason)
            is InstanceManager.Result.Joined -> Opened.Failed("Ezekiel rooms can't be joined yet.")
            is InstanceManager.Result.Created -> {
                val origin = checkNotNull(manager.resolveCoord(result.session, SPAWN))
                val fight = EzekielFight(result.session, origin, deps, hpBar, interactions, events)
                for (npc in fight.open()) manager.attachNpc(result.session.id, npc)
                fights[result.session.id] = fight
                Opened.Room(fight, result.enter)
            }
        }

    internal fun close(id: InstanceId) {
        fights.remove(id)?.dispose()
    }

    companion object {
        const val KEY = "tfa_ezekiel"

        private val ENTRANCE = CoordGrid(EzekielArena.ENTRY_X, EzekielArena.ENTRY_Z, 0)
        private val SPAWN = CoordGrid(EzekielArena.SPAWN_X, EzekielArena.SPAWN_Z, 0)
        private const val MAP_SQUARE_SHIFT = 6
        private const val REGION_X_SHIFT = 8
        private const val MAX_PARTY = 8

        private val SPEC =
            InstanceSpec(
                fee = 0,
                maxPlayers = MAX_PARTY,
                reclaimTicks = 0,
                graceTicks = 0,
                destroyWhenEmpty = true,
                area =
                    InstanceArea.copyRegions(
                        regionIds = listOf(regionId(ENTRANCE)),
                        level = ENTRANCE.level,
                        enterCoord = local(ENTRANCE),
                        exitCoord = TfaVault.LOBBY,
                    ),
                settingsRowId = 0,
            )

        private fun regionId(tile: CoordGrid): Int =
            ((tile.x shr MAP_SQUARE_SHIFT) shl REGION_X_SHIFT) or (tile.z shr MAP_SQUARE_SHIFT)

        private fun local(tile: CoordGrid) =
            RegionLocal(tile.level, tile.mx, tile.mz, tile.lx, tile.lz)
    }
}
