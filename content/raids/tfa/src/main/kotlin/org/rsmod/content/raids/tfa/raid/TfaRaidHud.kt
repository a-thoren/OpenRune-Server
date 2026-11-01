package org.rsmod.content.raids.tfa.raid

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.rsmod.api.instances.InstanceId
import org.rsmod.api.instances.InstanceManager
import org.rsmod.api.player.output.runClientScript
import org.rsmod.api.player.stat.baseHitpointsLvl
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.ui.ifCloseOverlay
import org.rsmod.api.player.ui.ifOpenFullOverlay
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.content.raids.party.RaidParty
import org.rsmod.content.raids.tfa.party.TfaLobby
import org.rsmod.content.raids.tfa.party.TfaPartySettings
import org.rsmod.content.raids.tfa.raid.encounter.bork.BorkEncounter
import org.rsmod.content.raids.tfa.raid.encounter.dreadnoth.DreadnothEncounter
import org.rsmod.content.raids.tfa.raid.encounter.ezekiel.EzekielEncounter
import org.rsmod.content.raids.tfa.raid.encounter.guardian.GuardianEncounter
import org.rsmod.content.raids.tfa.raid.encounter.ocellus.OcellusEncounter
import org.rsmod.content.raids.tfa.raid.encounter.xarth.XarthEncounter
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.queue.WorldQueueList

/** The in-raid party overlay: member health orbs, names and the raid timer. */
@Singleton
class TfaRaidHud
@Inject
constructor(
    private val lobby: TfaLobby,
    private val raids: TfaRaids,
    private val manager: InstanceManager,
    private val encounter: GuardianEncounter,
    private val ocellus: OcellusEncounter,
    private val dreadnoth: DreadnothEncounter,
    private val bork: BorkEncounter,
    private val xarth: XarthEncounter,
    private val ezekiel: EzekielEncounter,
    private val worldQueues: WorldQueueList,
    private val eventBus: EventBus,
) {
    private val shown = HashMap<InstanceId, MutableSet<Player>>()

    fun track(id: InstanceId) {
        shown[id] = mutableSetOf()
        schedule(id)
    }

    private fun schedule(id: InstanceId) {
        worldQueues.add(1) {
            if (refresh(id)) schedule(id) else close(id)
        }
    }

    private fun refresh(id: InstanceId): Boolean {
        val party = lobby.raidParty(id) ?: return false
        val visible = shown[id] ?: return false
        val inside = party.members.filter { manager.sessionForPlayer(it)?.id == id }
        for (left in visible.filter { it !in inside }) {
            visible.remove(left)
            closeFor(left)
        }
        val fight: TfaFight? =
            encounter.of(id)
                ?: ocellus.of(id)
                ?: dreadnoth.of(id)
                ?: bork.of(id)
                ?: xarth.of(id)
                ?: ezekiel.of(id)
        for (member in inside) {
            if (visible.add(member)) open(member, party)
            sendStates(member, party, inside)
            sendTimer(member, fight?.elapsedTicks, fight?.finished == true)
        }
        return true
    }

    private fun close(id: InstanceId) {
        shown.remove(id)?.forEach(::closeFor)
    }

    private fun closeFor(player: Player) {
        if (player.pendingLogout || player.loggingOut) return
        player.ifCloseOverlay(INTERFACE, eventBus)
        lobby.sync(player)
    }

    private fun open(player: Player, party: RaidParty<TfaPartySettings>) {
        player.ifOpenFullOverlay(INTERFACE, eventBus)
        lobby.markInRaid(player)
        VarPlayerIntMapSetter.set(player, PARTY_SLOT, party.members.indexOf(player) + 1)
        val names = Array(PARTY_CAPACITY) { party.members.getOrNull(it)?.displayName.orEmpty() }
        player.runClientScript(NAMES_SCRIPT.asRSCM(RSCMType.CLIENTSCRIPT), *names)
    }

    private fun sendStates(
        viewer: Player,
        party: RaidParty<TfaPartySettings>,
        inside: List<Player>,
    ) {
        for (slot in 0 until PARTY_CAPACITY) {
            val member = party.members.getOrNull(slot)
            val state =
                when {
                    member == null -> STATE_EMPTY
                    member !in inside -> STATE_NOT_ENTERED
                    member.hitpoints <= 0 || raids.isDead(member) -> STATE_DEAD
                    else -> healthState(member)
                }
            VarPlayerIntMapSetter.set(viewer, "$STATE_VARBIT$slot", state)
        }
    }

    private fun healthState(player: Player): Int {
        val max = player.baseHitpointsLvl.coerceAtLeast(1)
        val scaled = (player.hitpoints * HEALTH_STEPS / max).coerceIn(0, HEALTH_STEPS)
        return STATE_ZERO_HEALTH + scaled
    }

    private fun sendTimer(viewer: Player, elapsed: Int?, frozen: Boolean) {
        val script = TIMER_SCRIPT.asRSCM(RSCMType.CLIENTSCRIPT)
        when {
            elapsed == null -> viewer.runClientScript(script, 0, STATIC)
            frozen -> viewer.runClientScript(script, elapsed, STATIC)
            else -> viewer.runClientScript(script, elapsed, RUNNING)
        }
    }

    private companion object {
        const val INTERFACE = "interface.tfa_hud"
        const val PARTY_SLOT = "varbit.tfa_client_partyslot"
        const val STATE_VARBIT = "varbit.tfa_client_p"
        const val NAMES_SCRIPT = "clientscript.tfa_hud_names"
        const val TIMER_SCRIPT = "clientscript.tfa_hud_timer"

        const val PARTY_CAPACITY = 8
        const val STATE_EMPTY = 0
        const val STATE_ZERO_HEALTH = 1
        const val STATE_FULL_HEALTH = 27
        const val STATE_DEAD = 30
        const val STATE_NOT_ENTERED = 31
        const val HEALTH_STEPS = STATE_FULL_HEALTH - STATE_ZERO_HEALTH

        const val RUNNING = 0
        const val STATIC = 1
    }
}
