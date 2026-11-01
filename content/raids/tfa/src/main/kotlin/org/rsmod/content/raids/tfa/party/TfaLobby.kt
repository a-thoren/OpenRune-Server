package org.rsmod.content.raids.tfa.party

import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.rsmod.api.instances.InstanceId
import org.rsmod.api.player.input.ResumePauseButtonInput
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.ui.ifSetText
import org.rsmod.api.player.vars.intVarBit
import org.rsmod.api.player.vars.intVarp
import org.rsmod.content.raids.party.RaidParty
import org.rsmod.content.raids.tfa.raid.TfaRaids
import org.rsmod.content.raids.tfa.raid.nexus.TfaSupplyCredits
import org.rsmod.game.entity.Player

private var Player.partyStatus by intVarBit("varbit.tfa_client_partystatus")
private var Player.partyController by intVarp("varp.tfa_mycontroller")

/** What the lobby client shows about a player's party, kept in step with [TfaParties]. */
@Singleton
class TfaLobby @Inject constructor(private val parties: TfaParties, private val raids: TfaRaids) {
    private val raidParties = HashMap<InstanceId, RaidParty<TfaPartySettings>>()
    private val moving = HashSet<RaidParty<TfaPartySettings>>()

    fun create(leader: Player, now: Int): RaidParty<TfaPartySettings>? {
        val party = parties.create(leader, TfaPartySettings(), now) ?: return null
        markInParty(leader)
        refreshLobbyHud(party)
        return party
    }

    fun bindRaid(id: InstanceId, party: RaidParty<TfaPartySettings>) {
        raidParties[id] = party
    }

    fun raidParty(id: InstanceId): RaidParty<TfaPartySettings>? = raidParties[id]

    fun raidEnded(id: InstanceId) {
        val party = raidParties.remove(id) ?: return
        // The party moved on to its next room; only the last room ending ends the raid.
        if (party in raidParties.values || party in moving) return
        endRaid(party)
    }

    /** Runs [move] (a room change) without the emptied room's end disbanding [party]. */
    fun <T> moving(party: RaidParty<TfaPartySettings>, move: () -> T): T {
        moving += party
        try {
            return move()
        } finally {
            moving -= party
        }
    }

    fun endRaid(party: RaidParty<TfaPartySettings>) {
        raids.end(party)
        for (member in parties.disband(party)) {
            markNotInParty(member)
            sendLobbyHud(member)
            refreshDetails(member)
        }
    }

    fun markInParty(player: Player) {
        player.partyController = IN_PARTY
        player.partyStatus = STATUS_IN_PARTY
    }

    fun markInRaid(player: Player) {
        player.partyController = IN_RAID
        player.partyStatus = STATUS_IN_PARTY
    }

    fun wasInRaid(player: Player): Boolean = player.partyController == IN_RAID

    fun markNotInParty(player: Player) {
        player.partyController = NOT_IN_PARTY
        player.partyStatus = STATUS_NONE
        // Supply Credits belong to one raid (tsps estimate).
        TfaSupplyCredits.reset(player)
    }

    fun markStepInside(player: Player) {
        player.partyStatus = STATUS_STEP_INSIDE
    }

    fun sync(player: Player) {
        if (parties.partyOf(player) != null) markInParty(player) else markNotInParty(player)
    }

    fun markViewing(player: Player) {
        player.partyController = if (parties.partyOf(player) != null) IN_PARTY else NOT_IN_PARTY
    }

    /** Takes [player] out of any party or application, telling whoever is left. */
    fun leave(player: Player): Boolean {
        parties.withdraw(player)?.let { refreshViewers(it, exclude = player) }
        val left = parties.onLogout(player) ?: return false
        markNotInParty(player)
        sendLobbyHud(player)
        if (left.disbanded) {
            for (applicant in left.released) {
                applicant.mes("The party to which you were applying has disbanded.")
                refreshDetails(applicant)
            }
        } else {
            refreshLobbyHud(left.party)
            refreshViewers(left.party, exclude = player)
        }
        return true
    }

    fun sendLobbyHud(player: Player) {
        if (!player.ui.containsOverlay(LOBBY_INTERFACE)) return
        val names = parties.partyOf(player)?.members?.map { it.displayName }.orEmpty()
        player.ifSetText(LOBBY_NAMES, TfaPartyFormat.lobbyNames(names))
    }

    fun refreshLobbyHud(party: RaidParty<TfaPartySettings>) {
        for (member in party.members) sendLobbyHud(member)
    }

    fun refreshViewers(party: RaidParty<TfaPartySettings>, exclude: Player? = null) {
        for (viewer in party.members + party.applicants) {
            if (viewer !== exclude) refreshDetails(viewer)
        }
    }

    fun refreshDetails(viewer: Player) {
        if (!viewer.ui.containsModal(DETAILS_INTERFACE)) return
        val coroutine = viewer.activeCoroutine ?: return
        if (!coroutine.isAwaiting(ResumePauseButtonInput::class)) return
        viewer.resumeActiveCoroutine(ResumePauseButtonInput(DETAILS_BUTTONS, REFRESH))
    }

    companion object {
        const val LOBBY_INTERFACE = "interface.tfa_lobby"
        const val LOBBY_NAMES = "component.tfa_lobby:names"
        const val DETAILS_INTERFACE = "interface.tfa_partydetails"
        const val DETAILS_BUTTONS = "component.tfa_partydetails:pausebuttons"
        const val REFRESH = 1

        private const val NOT_IN_PARTY = -1
        private const val IN_PARTY = 0
        private const val IN_RAID = 1
        private const val STATUS_NONE = 0
        private const val STATUS_IN_PARTY = 1
        private const val STATUS_STEP_INSIDE = 2
    }
}
