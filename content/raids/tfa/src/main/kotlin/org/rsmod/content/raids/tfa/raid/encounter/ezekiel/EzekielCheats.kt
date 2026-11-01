package org.rsmod.content.raids.tfa.raid.encounter.ezekiel

import dev.or2.central.account.Rights
import jakarta.inject.Inject
import org.rsmod.api.instances.InstanceManager
import org.rsmod.api.player.hook.TeleportType
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.script.onCommand
import org.rsmod.game.MapClock
import org.rsmod.game.cheat.Cheat
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class EzekielCheats
@Inject
constructor(
    private val launcher: ProtectedAccessLauncher,
    private val ezekiel: EzekielEncounter,
    private val manager: InstanceManager,
    private val clock: MapClock,
) : PluginScript() {
    override fun ScriptContext.startup() {
        onCommand("tfaezekiel") {
            requiredRights = Rights.ADMINISTRATOR
            desc = "Open a fresh Ezekiel instance and teleport into his arena"
            cheat { toEzekiel() }
        }
        onCommand("ezekiel") {
            requiredRights = Rights.ADMINISTRATOR
            desc = USAGE
            cheat { ezekielCommand() }
        }
    }

    private fun Cheat.ezekielCommand() {
        val fight = ezekiel.at(player.coords)
        if (fight == null) {
            player.mes("You aren't in an Ezekiel room.")
            return
        }
        when (args.firstOrNull()) {
            "start" ->
                launcher.launch(player, busyText = "Close your dialog first.") {
                    telejump(fight.arenaEntry(), TeleportType.Exempt)
                    fight.ready(player)
                }
            "hp" -> {
                val percent = args.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100)
                if (percent == null) player.mes("Usage: ::ezekiel hp <percent>")
                else applied(fight.setHealthPercent(percent))
            }
            "special" -> fight.run(player, "special")
            "weaken" -> fight.run(player, "weaken")
            "finale" -> fight.run(player, "finale")
            "debug" -> fight.debugState().forEach(player::mes)
            "leave" -> leave()
            else -> player.mes("Usage: $USAGE")
        }
    }

    private fun Cheat.applied(done: Boolean) {
        player.mes(if (done) "Done." else "Ezekiel isn't in a fight. Use ::ezekiel start first.")
    }

    private fun Cheat.leave() {
        val session = manager.sessionForPlayer(player)
        if (session == null) {
            player.mes("You aren't in an instance.")
            return
        }
        val exit = manager.leave(player, session, clock.cycle)
        launcher.launch(player, busyText = "Close your dialog first.") {
            telejump(exit, TeleportType.Exempt)
        }
    }

    /** Ezekiel has no place in the raid's room order yet, so this is the only way in. */
    private fun Cheat.toEzekiel() {
        manager.sessionForPlayer(player)?.let { manager.leave(player, it, clock.cycle) }
        launcher.launch(player, busyText = "Close your dialog first.") {
            when (val opened = ezekiel.open(player)) {
                is EzekielEncounter.Opened.Failed -> mes(opened.reason)
                is EzekielEncounter.Opened.Room -> enter(opened)
            }
        }
    }

    private fun ProtectedAccess.enter(room: EzekielEncounter.Opened.Room) {
        telejump(room.enter, TeleportType.Exempt)
        manager.finalizeEntry(player, room.fight.session, clock.cycle)
        mes("Use ::ezekiel start to enter the arena and begin.")
    }

    private companion object {
        const val USAGE =
            "Ezekiel testing: start | hp <pct> | special | weaken | finale | debug | leave"
    }
}
