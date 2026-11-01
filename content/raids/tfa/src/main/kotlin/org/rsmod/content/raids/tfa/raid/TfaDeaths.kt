package org.rsmod.content.raids.tfa.raid

import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.rsmod.api.attr.AttributeKey
import org.rsmod.api.death.PlayerDeathCleanupHook
import org.rsmod.api.death.PlayerRespawnHook
import org.rsmod.api.instances.InstanceId
import org.rsmod.api.instances.InstanceManager
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.content.raids.tfa.party.TfaLobby
import org.rsmod.content.raids.tfa.raid.encounter.bork.BorkEncounter
import org.rsmod.content.raids.tfa.raid.encounter.dreadnoth.DreadnothEncounter
import org.rsmod.content.raids.tfa.raid.encounter.ezekiel.EzekielEncounter
import org.rsmod.content.raids.tfa.raid.encounter.guardian.GuardianEncounter
import org.rsmod.content.raids.tfa.raid.encounter.ocellus.OcellusEncounter
import org.rsmod.content.raids.tfa.raid.encounter.xarth.XarthEncounter
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.util.PathingEntityCommon
import org.rsmod.game.queue.WorldQueueList
import org.rsmod.map.CoordGrid
import org.rsmod.routefinder.collision.CollisionFlagMap

/** Saved across raids, as live counts them (tsps). */
private val TOTAL_DEATHS = AttributeKey<Int>(persistenceKey = "tfa_total_deaths")

/**
 * Deaths in the Archive (tsps captures, `RaidState.FracturedArchive.js`): a death in a challenge
 * that teammates are still fighting leaves the player watching from the room's corner until it is
 * won; the last fighter falling wipes the party, which is sent to the lobby in front of the Archive
 * Door. Items are kept on every death (the generic instance death rule); live loses them on a wipe.
 */
@Singleton
class TfaDeaths
@Inject
constructor(
    private val lobby: TfaLobby,
    private val raids: TfaRaids,
    private val manager: InstanceManager,
    private val guardian: GuardianEncounter,
    private val bork: BorkEncounter,
    private val ezekiel: EzekielEncounter,
    private val dreadnoth: DreadnothEncounter,
    private val xarth: XarthEncounter,
    private val ocellus: OcellusEncounter,
    private val worldQueues: WorldQueueList,
    private val collision: CollisionFlagMap,
    private val clock: MapClock,
) {
    init {
        manager.deathKeepChecks += ::keepsInstance
    }

    internal fun fight(id: InstanceId): TfaFight? =
        guardian.of(id)
            ?: bork.of(id)
            ?: ezekiel.of(id)
            ?: dreadnoth.of(id)
            ?: xarth.of(id)
            ?: ocellus.of(id)

    private fun inChallenge(id: InstanceId): Boolean {
        val fight = fight(id) ?: return false
        return fight.elapsedTicks != null && !fight.finished
    }

    private fun raidRoom(player: Player): Pair<TfaRaid, InstanceId>? {
        val raid = raids.of(player) ?: return null
        val id = raid.roomId ?: return null
        if (raid.wiping || lobby.raidParty(id) !== raid.party) return null
        return raid to id
    }

    private fun standing(raid: TfaRaid, id: InstanceId, except: Player): List<Player> =
        raid.party.members.filter {
            it !== except &&
                it !in raid.dead &&
                it.hitpoints > 0 &&
                manager.sessionForPlayer(it)?.id == id
        }

    /** The instance keeps everyone but the last to fall: that death is the wipe. */
    private fun keepsInstance(player: Player): Boolean {
        val (raid, id) = raidRoom(player) ?: return false
        if (manager.sessionForPlayer(player)?.id != id) return false
        return !inChallenge(id) || standing(raid, id, player).isNotEmpty()
    }

    internal fun respawnCoords(player: Player): CoordGrid? {
        val (raid, id) = raidRoom(player) ?: return null
        val session = manager.sessionForId(id) ?: return null
        if (manager.sessionForPlayer(player)?.id != id) return null
        if (!inChallenge(id)) return raid.room.arrival?.let { manager.resolveCoord(session, it) }
        if (standing(raid, id, player).isEmpty()) return WIPE_RETURN
        val corner = raid.room.spectate ?: raid.room.arrival ?: return null
        return manager.resolveCoord(session, corner)
    }

    internal fun onDeath(player: Player) {
        val raid = raids.of(player) ?: return
        val id = raid.roomId ?: return
        if (raid.wiping || lobby.raidParty(id) !== raid.party) return
        val total = (player.attr[TOTAL_DEATHS] ?: 0) + 1
        player.attr[TOTAL_DEATHS] = total
        player.mes("You have died. Total deaths: <col=ff0000>$total</col>.")
        for (other in raid.party.members) {
            if (other === player) continue
            other.mes(
                "<col=ff0000>${player.displayName}</col> has died. " +
                    "Total deaths: <col=ff0000>$total</col>."
            )
        }
        if (!inChallenge(id)) return
        raid.deaths++
        if (standing(raid, id, player).isEmpty()) {
            wipe(raid, id)
            return
        }
        player.mes("You will respawn when your party completes or fails the challenge.")
        player.mes("Spectating the room.")
        val session = manager.sessionForId(id) ?: return
        val camera = raid.room.camera?.let { manager.resolveCoord(session, it) }
        raids.spectate(raid, player, camera)
        watch(raid, id)
    }

    /**
     * The dead get up at the room's entrance once the challenge is won (captured). Rooms that
     * publish [TfaRoomCleared] revive them there too; this also covers Ocellus, which hands
     * straight over to the vault.
     */
    private fun watch(raid: TfaRaid, id: InstanceId) {
        worldQueues.add(1) {
            if (raid.wiping || raid.roomId != id || raid.dead.isEmpty()) return@add
            if (fight(id)?.finished != true) {
                watch(raid, id)
                return@add
            }
            val session = manager.sessionForId(id) ?: return@add
            val arrival = raid.room.arrival?.let { manager.resolveCoord(session, it) }
            for (player in raid.dead.toList()) {
                raids.stopSpectating(raid, player)
                if (arrival != null && manager.sessionForPlayer(player)?.id == id) {
                    PathingEntityCommon.telejump(player, collision, arrival)
                }
            }
        }
    }

    /**
     * Nobody is left standing (captured): two ticks after the last death "You failed to survive the
     * Fractured Archive.", three later everyone is in front of the Archive Door.
     */
    private fun wipe(raid: TfaRaid, id: InstanceId) {
        raid.wiping = true
        worldQueues.add(WIPE_DELAY) {
            val party = raid.party.members.toList()
            for (member in party) member.mes("You failed to survive the Fractured Archive.")
            worldQueues.add(WIPE_MOVE_DELAY) {
                val session = manager.sessionForId(id)
                for (member in party) {
                    raids.stopSpectating(raid, member)
                    if (session == null || manager.sessionForPlayer(member)?.id != id) continue
                    manager.leave(member, session, clock.cycle)
                    PathingEntityCommon.telejump(member, collision, WIPE_RETURN)
                }
                lobby.endRaid(raid.party)
            }
        }
    }

    private companion object {
        /** In the lobby, in front of the Archive Door (captured). */
        val WIPE_RETURN = CoordGrid(4130, 5560, 0)
        const val WIPE_DELAY = 2
        const val WIPE_MOVE_DELAY = 3
    }
}

internal class TfaRespawnHook @Inject constructor(private val deaths: TfaDeaths) :
    PlayerRespawnHook {
    override fun respawnCoords(player: Player): CoordGrid? = deaths.respawnCoords(player)
}

internal class TfaDeathCleanupHook @Inject constructor(private val deaths: TfaDeaths) :
    PlayerDeathCleanupHook {
    override fun cleanup(player: Player) = deaths.onDeath(player)
}
