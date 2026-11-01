package org.rsmod.content.raids.tfa.raid

import jakarta.inject.Singleton
import org.rsmod.api.instances.InstanceId
import org.rsmod.api.player.output.Camera
import org.rsmod.api.player.vars.intVarBit
import org.rsmod.content.raids.party.RaidParty
import org.rsmod.content.raids.tfa.party.TfaPartySettings
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.LocInfo
import org.rsmod.map.CoordGrid

private var Player.spectateMode by intVarBit("varbit.tfa_client_spectate_mode")

/** A side of the Nexus. The first door a party takes in a section picks it for the whole party. */
internal enum class TfaSide {
    West,
    East,
}

/**
 * One party's run through the Archive: the room it is in, how far it has got, the side it took in
 * each section, who is dead and watching, and the doors placed in its current room.
 */
internal class TfaRaid(val party: RaidParty<TfaPartySettings>) {
    var room: TfaRoom = TfaRoom.Guardian
    var roomId: InstanceId? = null

    /** Sections won past the Guardian (0-3); the Ocellus door opens at 3. */
    var cleared = 0
    val path = HashMap<Int, TfaSide>()
    val dead = HashSet<Player>()

    /** Deaths in this raid; any opens the Quartermaster's failure stock (tsps estimate). */
    var deaths = 0
    var wiping = false

    /** The instance [doors] and [ocellusDoor] were placed in. */
    var doorRoom: InstanceId? = null
    val doors = HashMap<Pair<Int, TfaSide>, LocInfo>()
    var ocellusDoor: LocInfo? = null
}

@Singleton
class TfaRaids {
    private val raids = HashMap<RaidParty<TfaPartySettings>, TfaRaid>()

    internal fun start(party: RaidParty<TfaPartySettings>): TfaRaid {
        raids.remove(party)?.let(::release)
        return TfaRaid(party).also { raids[party] = it }
    }

    internal fun of(party: RaidParty<TfaPartySettings>): TfaRaid? = raids[party]

    internal fun of(player: Player): TfaRaid? =
        raids.values.firstOrNull { player in it.party.members }

    fun isDead(player: Player): Boolean = of(player)?.dead?.contains(player) == true

    fun end(party: RaidParty<TfaPartySettings>) {
        raids.remove(party)?.let(::release)
    }

    private fun release(raid: TfaRaid) {
        for (player in raid.dead.toList()) stopSpectating(raid, player)
    }

    /** Dead in a challenge: frozen in the room's corner with the camera on the fight. */
    internal fun spectate(raid: TfaRaid, player: Player, camera: CoordGrid?) {
        raid.dead += player
        player.frozen = true
        player.spectateMode = SPECTATE_DEAD
        if (camera != null) {
            Camera.camMoveTo(player, camera.translateZ(-CAMERA_BACK), CAMERA_HEIGHT, RATE, RATE)
            Camera.camLookAt(player, camera, LOOK_HEIGHT, RATE, RATE)
        }
    }

    internal fun stopSpectating(raid: TfaRaid, player: Player) {
        if (!raid.dead.remove(player)) return
        player.frozen = false
        player.spectateMode = 0
        if (player.isSlotAssigned) Camera.camReset(player)
    }

    private companion object {
        /** `tfa_client_spectate_mode`: 1 for the dead, 2 for outside spectators (captured). */
        const val SPECTATE_DEAD = 1

        // tunable: live uses cam_target on the room's middle; these approximate it.
        const val CAMERA_BACK = 14
        const val CAMERA_HEIGHT = 1800
        const val LOOK_HEIGHT = 0
        const val RATE = 100
    }
}
