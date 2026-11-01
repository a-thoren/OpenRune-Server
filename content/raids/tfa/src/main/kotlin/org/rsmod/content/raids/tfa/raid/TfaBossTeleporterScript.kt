package org.rsmod.content.raids.tfa.raid

import jakarta.inject.Inject
import org.rsmod.api.player.hook.TeleportType
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onOpLoc1
import org.rsmod.content.raids.tfa.raid.encounter.bork.BorkEncounter
import org.rsmod.content.raids.tfa.raid.encounter.ezekiel.EzekielEncounter
import org.rsmod.content.raids.tfa.raid.encounter.xarth.XarthEncounter
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/** The shared Archive transporter: sends the player into whichever boss room they stand in. */
class TfaBossTeleporterScript
@Inject
constructor(
    private val bork: BorkEncounter,
    private val xarth: XarthEncounter,
    private val ezekiel: EzekielEncounter,
) : PluginScript() {
    override fun ScriptContext.startup() {
        onOpLoc1("loc.tfa_boss_teleporter") { touch() }
    }

    private suspend fun ProtectedAccess.touch() {
        val borkFight = bork.at(player.coords)
        val xarthFight = xarth.at(player.coords)
        val ezekielFight = ezekiel.at(player.coords)
        val fight: TfaFight = borkFight ?: xarthFight ?: ezekielFight ?: return
        if (fight.elapsedTicks != null) {
            mes("Your party has already started the challenge. You can't join them now.")
            return
        }
        val begin = choice2("Yes.", true, "No.", false, title = "Begin the challenge?")
        if (!begin) return
        if (borkFight != null) {
            telejump(borkFight.arenaEntry(), TeleportType.Exempt)
            borkFight.ready(player)
        } else if (xarthFight != null) {
            telejump(xarthFight.arenaEntry(), TeleportType.Exempt)
            xarthFight.ready(player)
        } else if (ezekielFight != null) {
            telejump(ezekielFight.arenaEntry(), TeleportType.Exempt)
            ezekielFight.ready(player)
        }
    }
}
